import hashlib
import json
from pathlib import Path
import secrets
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request

from server import COOKIE, create_app
from source_auth import SourceError


PREFIX = '/app/feiniu-karaoke'


class WebClientApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.cfg = {'base_path': PREFIX, 'lan_networks': [], 'username': 'synthetic-user',
            'password_salt': secrets.token_hex(16), 'password_hash': '', 'session_key': secrets.token_hex(32),
            'music_url': 'https://synthetic-nas/music', 'music_token': 'synthetic-private-token',
            'catalog_sync_enabled': False, 'score_generation_enabled': False,
            'cache_dir': str(self.folder / 'cache'), 'score_cache_dir': str(self.folder / 'scores'),
            'video_cover_cache': str(self.folder / 'covers')}
        self.app = create_app(self.cfg, self.folder / 'state.db')
        self.b = self.app['bridge']
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.auth = {'Cookie': COOKIE + '=' + self.b.session()}
        self.upstreams = []

    async def asyncTearDown(self):
        await self.client.close()
        for upstream in self.upstreams:
            await upstream.close()
        self.temp.cleanup()

    def song(self, sid, title, *, visible=True, guid=None, **metadata):
        guid = guid or 'fixture' + str(sid)
        data = {'title': title, 'artist': 'Artist', 'media_kind': 'audio', **metadata}
        with self.b.db:
            self.b.db.execute('INSERT INTO songs VALUES(?,?,?)', (sid, guid, json.dumps(data)))
            if visible:
                self.b.db.execute('INSERT INTO current_guids VALUES(?)', (guid,))

    async def get(self, path):
        result = await self.client.get(PREFIX + path, headers=self.auth)
        self.assertEqual(result.status, 200)
        return await result.json()

    async def test_groups_and_filtered_pagination_only_include_current_snapshot(self):
        self.song(1, 'First & song', album='Album A', genre='Pop', language='国语')
        self.song(2, 'Second', album='Album A', genre='Pop', language='粤语')
        self.song(3, 'Hidden', album='Private old album', visible=False)
        self.song(4, 'Other', album='Album B', genre='Rock', language='英语')
        result = await self.get('/api/web/catalog/groups?kind=album')
        self.assertEqual(result['total'], 2)
        self.assertEqual([item['value'] for item in result['items']], ['Album A', 'Album B'])
        self.assertEqual(result['items'][0]['count'], 2)
        songs = await self.get('/api/web/catalog/songs?kind=album&value=Album%20A&pageSize=1&page=2')
        self.assertEqual(songs['total'], 2)
        self.assertEqual(songs['items'][0]['song_id'], 2)
        songs = await self.get('/api/web/catalog/songs?kind=genre&value=Pop&q=FIRST')
        self.assertEqual(songs['items'][0]['id'], 1)
        self.assertEqual((await self.client.get(PREFIX + '/api/web/catalog/groups?kind=sql', headers=self.auth)).status, 400)
        self.assertEqual((await self.client.get(PREFIX + '/api/web/catalog/groups?kind=album')).status, 401)

    async def test_etag_invalidates_on_snapshot_change(self):
        self.song(1, 'First', album='A')
        path = PREFIX + '/api/web/catalog/groups?kind=album'
        first = await self.client.get(path, headers=self.auth)
        etag = first.headers['ETag']
        response = await self.client.get(path, headers={**self.auth, 'If-None-Match': etag})
        self.assertEqual(response.status, 304)
        self.song(2, 'Second', album='B')
        response = await self.client.get(path, headers={**self.auth, 'If-None-Match': etag})
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())['total'], 2)

    async def test_context_is_opaque_stable_between_syncs_and_changes_by_source_or_user(self):
        first = await self.get('/api/web/context')
        serialized = json.dumps(first)
        for private in (self.cfg['username'], self.cfg['music_token'], self.cfg['session_key'], str(self.folder), self.cfg['music_url']):
            self.assertNotIn(private, serialized)
        self.song(1, 'Update')
        second = await self.get('/api/web/context')
        self.assertEqual(first['catalogScope'], second['catalogScope'])
        self.assertNotEqual(first['catalogToken'], second['catalogToken'])
        self.b.sources.music.data['scope'] = 'another-fixture-account'
        third = await self.get('/api/web/context')
        self.assertNotEqual(first['catalogScope'], third['catalogScope'])
        req = make_mocked_request('GET', '/api/web/context')
        req['fnos_identity'] = {'uid': '1001'}
        self.assertNotEqual(third['catalogScope'], self.b.web_catalog.context(req)['catalogScope'])

    async def test_audio_descriptor_uses_same_origin_and_only_prepared_accompaniment(self):
        self.song(1, 'Audio', duration=123.5)
        result = await self.get('/api/v2/playback/1')
        self.assertEqual(result['mediaKind'], 'audio')
        self.assertEqual(result['playUrl'], PREFIX + '/stream/1')
        self.assertEqual(result['fallbackUrl'], PREFIX + '/hls/1/master.m3u8')
        self.assertEqual(result['durationMs'], 123500)
        self.assertIsNone(result['accompanimentUrl'])
        folder = self.b.scores.folder(1)
        folder.mkdir(parents=True)
        (folder / 'accompaniment.m4a').write_bytes(b'fixture')
        result = await self.get('/api/v2/playback/1')
        self.assertEqual(result['accompanimentUrl'], PREFIX + '/api/accompaniment/1')
        self.assertNotIn('synthetic-private-token', json.dumps(result))

    async def test_mv_tracks_use_hls_renditions_without_inventing_roles(self):
        self.song(1, 'Video', guid='video:fixture', media_kind='mv')
        self.b.video_playback.descriptor = AsyncMock(return_value={'mediaKind': 'mv', 'transport': 'progressive',
            'playUrl': '/stream/1', 'audioTracks': [{'index': 1, 'codec': 'aac'}, {'index': 2, 'codec': 'aac'}]})
        result = await self.get('/api/v2/playback/1')
        self.assertEqual(result['transport'], 'hls')
        self.assertEqual(result['playUrl'], PREFIX + '/mv/1/master.m3u8')
        self.assertEqual([track['index'] for track in result['audioTracks']], [0, 1])
        self.assertEqual([track['streamIndex'] for track in result['audioTracks']], [1, 2])
        self.assertTrue(all('role' not in track for track in result['audioTracks']))

    async def test_static_ui_assets_public_mime_and_traversal_are_checked(self):
        root = self.folder / 'server'
        root.mkdir()
        (root / 'webapp.js').write_text('window.fixture=true;')
        (root / 'config.json').write_text('synthetic-secret')
        assets = root / 'web-assets' / 'vendor'
        assets.mkdir(parents=True)
        (assets / 'test.wasm').write_bytes(b'\0asm-fixture')
        with patch('server.__file__', str(root / 'server.py')):
            for prefix in ('', PREFIX):
                response = await self.client.get(prefix + '/player-assets/webapp.js')
                self.assertEqual(response.status, 200)
                self.assertEqual(response.headers['Content-Type'], 'text/javascript')
                self.assertEqual(response.headers['X-Content-Type-Options'], 'nosniff')
                response = await self.client.get(prefix + '/player-assets/vendor/test.wasm')
                self.assertEqual(response.status, 200)
                self.assertEqual(response.headers['Content-Type'], 'application/wasm')
                for path in ('config.json', '%2e%2e/config.json', 'vendor/%2e%2e/%2e%2e/config.json',
                             'vendor%5c..%5cconfig.json', 'C%3A/config.json'):
                    response = await self.client.get(prefix + '/player-assets/' + path)
                    # The client may normalize encoded '..' before transmission;
                    # that lands outside the public assets and must require auth.
                    self.assertIn(response.status, (401, 404), path)
                    self.assertNotIn('synthetic-secret', await response.text())

    async def test_fnvideo_scans_selected_libraries_and_proxy_keeps_credentials_private(self):
        calls = []
        async def upstream(request):
            calls.append((request.path, request.headers.get('Authorization'), request.headers.get('Range')))
            if request.path.endswith('/item/list'):
                self.assertEqual((await request.json())['ancestor_guid'], 'fixture-library')
                return web.json_response({'code': 0, 'data': {'total': 1, 'list': [
                    {'guid': 'fixture-item', 'title': 'Real fixture MV', 'type': 'Video', 'duration': 42}]}})
            if request.path.endswith('/play/info'):
                self.assertEqual((await request.json())['item_guid'], 'fixture-item')
                return web.json_response({'code': 0, 'data': {'media_guid': 'fixture-media'}})
            self.assertEqual(request.path, '/v/api/v1/media/range/fixture-media')
            self.assertEqual(request.headers['Cookie'], 'Trim-MC-token=synthetic-video-token')
            self.assertIn('sign=', request.headers['authx'])
            return web.Response(status=206, body=b'234', headers={'Content-Type': 'video/mp4',
                'Content-Range': 'bytes 2-4/10', 'Set-Cookie': 'synthetic-video-token'})
        app = web.Application()
        app.router.add_route('*', '/{tail:.*}', upstream)
        source = TestServer(app)
        await source.start_server()
        self.upstreams.append(source)
        self.b.sources.video.data = {'url': str(source.make_url('/v')), 'token': 'synthetic-video-token',
            'scope': 'fixture-video-scope', 'libraries': ['fixture-library']}
        await self.b.catalog.sync('video')
        row = self.b.db.execute("SELECT id FROM songs WHERE guid='fnvideo:fixture-video-scope:fixture-item'").fetchone()
        self.assertIsNotNone(row)
        sid = row[0]
        result = await self.get('/api/v2/playback/' + str(sid))
        self.assertEqual(result['playUrl'], PREFIX + '/stream/' + str(sid))
        response = await self.client.get(PREFIX + '/stream/' + str(sid), headers={**self.auth, 'Range': 'bytes=2-4'})
        self.assertEqual(response.status, 206)
        self.assertEqual(await response.read(), b'234')
        self.assertNotIn('Set-Cookie', response.headers)
        self.assertNotIn('synthetic-video-token', str(response.headers))
        self.assertEqual(calls[-1][2], 'bytes=2-4')
        self.b.sources.video.data['scope'] = 'switched-account'
        self.assertEqual((await self.client.get(PREFIX + '/api/v2/playback/' + str(sid), headers=self.auth)).status, 403)

    async def test_incomplete_video_snapshot_preserves_prior_catalog(self):
        self.song(1, 'Existing', guid='fnvideo:fixture:existing', media_kind='mv')
        source = self.b.sources.video
        source.data = {'token': 'fixture-token', 'scope': 'fixture', 'libraries': ['library']}
        source.raw = AsyncMock(return_value={'total': 2, 'list': []})
        await self.b.catalog.sync('video')
        self.assertEqual(self.b.catalog.status['video']['state'], 'offline')
        self.assertEqual(self.b.db.execute('SELECT count(*) FROM current_guids').fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
