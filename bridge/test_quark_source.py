import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
from pathlib import Path
import secrets
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import CookieJar
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request
from yarl import URL

from quark_source import QuarkSource, file_id, safe_media_url
from server import COOKIE, create_app
from source_auth import SourceError


PREFIX = '/app/feiniu-karaoke'


class Content:
    def __init__(self, chunks):
        self.chunks = chunks
    async def iter_chunked(self, size):
        for chunk in self.chunks:
            yield chunk


class Response:
    def __init__(self, status=200, headers=None, chunks=None):
        self.status, self.headers = status, headers or {}
        self.content = Content(chunks or [b'fixture-media'])
    def release(self):
        pass


class QuarkTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        cfg = {'base_path': PREFIX, 'lan_networks': [], 'username': 'fixture-service',
            'password_salt': secrets.token_hex(16), 'password_hash': '', 'session_key': secrets.token_hex(32),
            'catalog_sync_enabled': False, 'score_generation_enabled': False,
            'cache_dir': str(self.folder / 'cache'), 'score_cache_dir': str(self.folder / 'scores'),
            'video_cover_cache': str(self.folder / 'covers')}
        self.app = create_app(cfg, self.folder / 'state.db')
        self.b = self.app['bridge']
        self.q = self.b.sources.quark
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.auth = {'Cookie': COOKIE + '=' + self.b.session()}

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    def connect(self, scope='fixture-scope'):
        self.q.data = {'scope': scope, 'cookies': {'__puus': 'synthetic-private-cookie'},
            'mounts': [{'id': 'music-folder', 'name': 'Fixture Music'}], 'remember': True}
        self.q.state = 'authenticated'

    def song(self, sid=1, scope='fixture-scope', ident='audio-file'):
        guid = 'quark:' + scope + ':' + ident
        with self.b.db:
            self.b.db.execute('INSERT INTO songs VALUES(?,?,?)', (sid, guid, json.dumps({
                'title': 'Own media', 'artist': 'Artist', 'media_kind': 'audio', 'quark_fid': ident})))
            self.b.db.execute('INSERT INTO current_guids VALUES(?)', (guid,))
        return guid

    async def api(self, action, body=None):
        return await self.client.request('GET' if body is None else 'POST',
            PREFIX + '/api/v2/sources/quark/' + action, headers=self.auth, json=body)

    def provider(self, *, uid='synthetic-provider-user', verified=True):
        async def request(url, params=None, body=None, *, session=None, **kwargs):
            if url.endswith('/getTokenForQrcodeLogin'):
                return {'status': 2000000, 'data': {'members': {'token': 'synthetic-qr-token'}}}
            if url.endswith('/getServiceTicketByQrcodeToken'):
                return {'status': 2000000, 'data': {'members': {'service_ticket': 'synthetic-service-ticket'}}}
            if url.endswith('/account/info'):
                session.cookie_jar.update_cookies({'__puus': 'synthetic-private-cookie'}, response_url=URL('https://pan.quark.cn/'))
                return {'data': {'user_id': uid} if uid else {}}
            return {'status': 200 if verified else 401, 'data': {'list': []}}
        self.q.json_request = request

    async def test_official_qr_ticket_is_not_an_attempt_id_or_status_and_session_is_encrypted(self):
        self.provider()
        response = await self.api('begin', {'remember': True})
        data = await response.json()
        self.assertEqual(response.status, 200)
        self.assertNotEqual(data['attemptId'], 'synthetic-qr-token')
        self.assertTrue(data['qrLoginUrl'].startswith('https://su.quark.cn/'))
        response = await self.api('poll', {'attemptId': data['attemptId']})
        result = await response.json()
        self.assertEqual(result['state'], 'authorized')
        for private in ('synthetic-private-cookie', 'synthetic-service-ticket', 'synthetic-qr-token', 'synthetic-provider-user'):
            self.assertNotIn(private, json.dumps(result))
            self.assertNotIn(private.encode(), self.b.sources.vault.file.read_bytes())
        saved = self.b.sources.vault.load()
        self.assertEqual(saved['quark']['cookies']['__puus'], 'synthetic-private-cookie')
        self.assertEqual(self.q.attempts, {})

    async def test_confirmed_qr_requires_protected_drive_verification(self):
        self.provider(verified=False)
        result = await self.q.begin('principal')
        with self.assertRaises(SourceError):
            await self.q.poll(result['attemptId'], 'principal')
        self.assertFalse(self.q.authenticated)
        self.assertFalse(self.b.sources.vault.load().get('quark'))

    async def test_qr_attempt_is_bound_to_principal_and_expiry(self):
        self.provider()
        result = await self.q.begin('principal-a')
        with self.assertRaises(SourceError):
            await self.q.poll(result['attemptId'], 'principal-b')
        self.q.attempts[result['attemptId']]['expires'] = 0
        self.assertEqual(await self.q.poll(result['attemptId'], 'principal-a'), {'state': 'expired'})
        self.assertEqual(self.q.attempts, {})

    async def test_account_switch_and_logout_block_old_ids_and_remove_old_visibility(self):
        self.connect('old-account')
        guid = self.song(scope='old-account')
        request = make_mocked_request('GET', PREFIX + '/api/web/context', headers=self.auth)
        before = self.b.web_catalog.context(request)['catalogScope']
        self.provider()
        result = await self.q.begin('principal')
        self.assertEqual((await self.q.poll(result['attemptId'], 'principal'))['state'], 'authorized')
        self.assertEqual(self.b.db.execute('SELECT count(*) FROM current_guids').fetchone()[0], 0)
        self.assertNotEqual(before, self.b.web_catalog.context(request)['catalogScope'])
        with self.assertRaises(SourceError):
            self.q.resolve(guid)
        self.q.link = AsyncMock(side_effect=AssertionError('old identifier must never reach provider'))
        response = await self.client.get(PREFIX + '/stream/1', headers=self.auth)
        self.assertEqual(response.status, 403)
        self.q.link.assert_not_awaited()
        await self.q.logout()
        self.assertFalse(self.q.authenticated)
        self.assertEqual(self.q.links, {})

    async def test_restart_without_remembered_account_hides_old_cloud_catalog(self):
        self.connect()
        self.song()
        with self.b.db:
            self.b.db.execute("INSERT INTO current_guids VALUES('video:local-file')")
        restarted = QuarkSource(self.b.sources, {})
        self.assertFalse(restarted.authenticated)
        visible = [row[0] for row in self.b.db.execute('SELECT guid FROM current_guids')]
        self.assertEqual(visible, ['video:local-file'])

    async def test_restart_keeps_only_the_remembered_account_namespace(self):
        self.connect('remembered-account')
        current = self.song(scope='remembered-account')
        self.song(sid=2, scope='older-account', ident='other')
        restarted = QuarkSource(self.b.sources, self.q.data)
        self.assertTrue(restarted.authenticated)
        self.assertEqual([row[0] for row in self.b.db.execute('SELECT guid FROM current_guids')], [current])

    async def test_mount_and_scan_need_admin_and_mounted_directory_is_explicit(self):
        self.connect()
        self.q.list_folder = AsyncMock(return_value=([], 0))
        response = await self.client.post(PREFIX + '/api/v2/sources/quark/mount', json={'parent': 'music-folder'})
        self.assertEqual(response.status, 401)
        self.q.list_folder.assert_not_awaited()
        original = self.b.is_admin_session
        self.b.is_admin_session = lambda req: False
        response = await self.api('status')
        self.assertEqual(response.status, 401)
        self.b.is_admin_session = original
        await self.q.mount('chosen-folder', 'Chosen music')
        self.q.list_folder.assert_awaited_once_with('chosen-folder', 1)
        self.assertEqual(self.q.data['mounts'], [{'id': 'chosen-folder', 'name': 'Chosen music'}])
        self.assertNotIn('quark', self.b.catalog.tasks)  # Never schedule periodic whole-drive scans.

    async def test_logout_during_qr_verification_cannot_restore_credentials(self):
        self.provider()
        result = await self.q.begin('principal')
        entered, released = asyncio.Event(), asyncio.Event()
        calls = 0
        async def cancel_scan():
            nonlocal calls
            calls += 1
            if calls == 1:
                entered.set()
                await released.wait()
        self.q.cancel_scan = cancel_scan
        pending = asyncio.create_task(self.q.poll(result['attemptId'], 'principal'))
        await entered.wait()
        await self.q.logout()
        released.set()
        self.assertEqual(await pending, {'state': 'expired'})
        self.assertFalse(self.q.authenticated)
        self.assertFalse(self.b.sources.vault.load().get('quark', {}).get('cookies'))

    async def test_account_switch_during_mount_verification_does_not_mount_old_directory(self):
        self.connect('old-account')
        entered, released = asyncio.Event(), asyncio.Event()
        async def listing(*args):
            entered.set()
            await released.wait()
            return [], 0
        self.q.list_folder = listing
        pending = asyncio.create_task(self.q.mount('old-folder', 'Old account folder'))
        await entered.wait()
        await self.q.logout()
        self.connect('new-account')
        released.set()
        with self.assertRaises(SourceError):
            await pending
        self.assertEqual(self.q.data['mounts'], [{'id': 'music-folder', 'name': 'Fixture Music'}])

    async def test_recursive_pagination_and_local_lrc_are_associated_only_after_complete_scan(self):
        self.connect()
        calls = []
        first = [{'fid': 'a' + str(i), 'file_name': 'Singer - Song ' + str(i) + '.mp3'} for i in range(98)]
        first += [{'fid': 'child-folder', 'file_name': 'Child', 'dir': True}, {'fid': 'lrc0', 'file_name': 'Singer - Song 0.lrc'}]
        async def listing(parent, page):
            calls.append((parent, page))
            if parent == 'music-folder':
                return (first if page == 1 else [{'fid': 'a98', 'file_name': 'Singer - Song 98.mp3'}]), 101
            return [{'fid': 'mv1', 'file_name': 'Singer - Child MV.mp4'}], 1
        self.q.list_folder = listing
        await self.b.catalog.sync('quark')
        self.assertEqual(calls, [('music-folder', 1), ('music-folder', 2), ('child-folder', 1)])
        self.assertEqual(self.b.db.execute('SELECT count(*) FROM current_guids').fetchone()[0], 100)
        row = self.b.db.execute("SELECT data FROM songs WHERE guid='quark:fixture-scope:a0'").fetchone()
        self.assertEqual(json.loads(row[0])['quark_lyric'], 'lrc0')
        self.assertEqual(self.q.progress['songs'], 100)
        self.assertEqual(self.q.progress['checkedFolders'], 2)

    async def test_incomplete_or_repeating_scan_preserves_prior_complete_snapshot(self):
        self.connect()
        guid = self.song()
        self.q.list_folder = AsyncMock(return_value=([{'fid': 'new', 'file_name': 'New.mp3'}], 2))
        await self.q.start_scan()
        await self.q.scan_task
        self.assertEqual(self.q.progress['state'], 'error')
        self.assertEqual(self.b.db.execute('SELECT guid FROM current_guids').fetchone()[0], guid)
        self.assertEqual(self.b.catalog.status['quark']['state'], 'offline')
        self.assertIsNone(self.b.db.execute("SELECT id FROM songs WHERE guid='quark:fixture-scope:new'").fetchone())

    async def test_changing_directory_total_preserves_prior_complete_snapshot(self):
        self.connect()
        guid = self.song()
        first = [{'fid': 'a' + str(i), 'file_name': 'Song ' + str(i) + '.mp3'} for i in range(100)]
        async def listing(parent, page):
            return (first, 101) if page == 1 else ([{'fid': 'new', 'file_name': 'New.mp3'}], 102)
        self.q.list_folder = listing
        await self.b.catalog.sync('quark')
        self.assertEqual(self.b.db.execute('SELECT guid FROM current_guids').fetchone()[0], guid)
        self.assertEqual(self.b.catalog.status['quark']['state'], 'offline')

    async def test_proxy_range_follows_only_safe_domains_and_keeps_cookies_off_storage_cdn(self):
        self.connect()
        self.song()
        self.q.link = AsyncMock(return_value='https://media.quark.cn/file?sign=synthetic-signature')
        requests = []
        class Session:
            def __init__(inner, **kwargs):
                pass
            async def __aenter__(inner):
                return inner
            async def __aexit__(inner, *args):
                pass
            async def get(inner, address, **kwargs):
                requests.append((address, kwargs['headers']))
                if len(requests) == 1:
                    return Response(302, {'Location': 'https://bucket.aliyuncs.com/media?sign=synthetic-signature'})
                return Response(206, {'Content-Type': 'audio/mpeg', 'Content-Length': '3',
                    'Content-Range': 'bytes 2-4/10', 'Set-Cookie': 'synthetic-private-cookie'}, [b'234'])
        with patch('quark_source.ClientSession', Session):
            response = await self.client.get(PREFIX + '/stream/1', headers={**self.auth, 'Range': 'bytes=2-4'})
            self.assertEqual(response.status, 206)
            self.assertEqual(await response.read(), b'234')
            self.assertEqual(response.headers['Content-Range'], 'bytes 2-4/10')
            self.assertNotIn('Set-Cookie', response.headers)
            self.assertNotIn('synthetic-private-cookie', str(response.headers))
        self.assertEqual(requests[0][1]['Range'], 'bytes=2-4')
        self.assertIn('synthetic-private-cookie', requests[0][1]['Cookie'])
        self.assertNotIn('Cookie', requests[1][1])
        descriptor = await (await self.client.get(PREFIX + '/api/v2/playback/1', headers=self.auth)).json()
        self.assertEqual(descriptor['playUrl'], PREFIX + '/stream/1')
        self.assertNotIn('synthetic-signature', json.dumps(descriptor))
        self.assertNotIn('synthetic-private-cookie', json.dumps(descriptor))

    async def test_unsafe_redirect_and_invalid_range_do_not_contact_untrusted_host(self):
        self.connect()
        guid = self.song()
        self.q.link = AsyncMock(return_value='https://media.quark.cn/file')
        requests = []
        class Session:
            def __init__(inner, **kwargs): pass
            async def __aenter__(inner): return inner
            async def __aexit__(inner, *args): pass
            async def get(inner, address, **kwargs):
                requests.append(address)
                return Response(302, {'Location': 'https://evil.example/file'})
        with patch('quark_source.ClientSession', Session):
            with self.assertRaises(SourceError):
                async with self.q.media_response(guid):
                    self.fail('unsafe redirect was accepted')
            response = await self.client.get(PREFIX + '/stream/1', headers={**self.auth, 'Range': 'bytes=0-1,2-3'})
            self.assertEqual(response.status, 416)
        self.assertEqual(requests, ['https://media.quark.cn/file'])

    async def test_cloud_lrc_is_bounded_decoded_and_returns_no_signed_url(self):
        self.connect()
        guid = self.song()
        async def media(guid, requested=None):
            yield Response(200, chunks=['[00:00.00]真实歌词'.encode('gb18030')])
        self.q.media_response = asynccontextmanager(media)
        self.assertEqual(await self.q.lyrics(guid, 'lrc-file'), '[00:00.00]真实歌词')
        with self.b.db:
            self.b.db.execute('UPDATE songs SET data=? WHERE id=1', (json.dumps({
                'title': 'Song', 'artist': 'Artist', 'quark_lyric': 'lrc-file'}),))
        response = await self.client.get(PREFIX + '/api/lyrics/1', headers=self.auth)
        result = await response.json()
        self.assertEqual(result['content'], '[00:00.00]真实歌词')
        self.assertNotIn('synthetic-private-cookie', json.dumps(result))

    async def test_source_domains_and_file_ids_reject_external_urls(self):
        for address in ('https://media.quark.cn/file', 'https://cdn.quark.com/file', 'https://bucket.aliyuncs.com/file'):
            self.assertTrue(safe_media_url(address))
        for address in ('http://media.quark.cn/file', 'https://media.quark.cn.evil.example/file',
                'https://user:password@media.quark.cn/file', 'https://127.0.0.1/file', 'https://media.quark.cn:8443/file'):
            self.assertFalse(safe_media_url(address))
        for ident in ('../file', 'http://example', '', 'id?x=1'):
            with self.assertRaises(SourceError):
                file_id(ident)


if __name__ == '__main__':
    unittest.main()
