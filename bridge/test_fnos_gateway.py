import asyncio
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from aiohttp import ClientSession, UnixConnector, web
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request

from fnos_gateway import AppListeners, base_path, gateway_middleware, gateway_peer_uids, scoped_url
from server import COOKIE, Bridge, create_app


PREFIX = '/app/feiniu-karaoke'


def fixture_config(folder, **extra):
    salt = secrets.token_bytes(16)
    return dict(base_path=PREFIX, lan_networks=['192.168.1.0/24'],
                username='fixture', password_salt=salt.hex(),
                password_hash=hashlib.pbkdf2_hmac('sha256', b'fixture', salt, 200000).hex(),
                session_key=secrets.token_hex(32), catalog_sync_enabled=False,
                score_generation_enabled=False, cache_dir=str(folder/'cache'),
                video_cover_cache=str(folder/'covers'), **extra)


class GatewayRouteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.config = fixture_config(self.folder)
        self.app = create_app(self.config, self.folder/'state.db')
        self.bridge = self.app['bridge']
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.auth = {'Cookie': COOKIE+'='+self.bridge.session()}

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    async def test_unconfigured_install_serves_both_entry_points_and_safe_status(self):
        for path in ('/', '/m', '/sing', '/remote', '/sources', PREFIX, PREFIX+'/', PREFIX+'/m',
                     PREFIX+'/sing', PREFIX+'/remote', PREFIX+'/sources'):
            response = await self.client.get(path)
            self.assertEqual(response.status, 200, path)
            self.assertIn('text/html', response.headers['Content-Type'])
        self.assertEqual((await self.client.get(PREFIX+'/api/app/status')).status, 401)
        response = await self.client.get(PREFIX+'/api/app/status', headers=self.auth)
        status = await response.json()
        self.assertEqual(response.status, 200)
        self.assertEqual(status['basePath'], PREFIX)
        self.assertTrue(status['sourceSetupRequired'])
        self.assertFalse(status['gateway'])
        self.assertEqual(status['sources'], [{'id':'music','state':'unconfigured'}, {'id':'video','state':'unconfigured'}, {'id':'quark','state':'unconfigured'}])
        self.assertEqual(response.headers['Cache-Control'], 'no-store')
        for private in ('fixture', 'password', 'token', str(self.folder), self.config['session_key']):
            self.assertNotIn(private, json.dumps(status))
        self.assertTrue((await (await self.client.get('/api/v2/health', headers=self.auth)).json())['ok'])

    async def test_prefixed_login_cookie_scoped_and_tcp_forwarding_never_sets_secure(self):
        response = await self.client.post(PREFIX+'/api/tv-auth/login',
            headers={'X-Forwarded-Proto':'https', 'X-Trim-User':'admin'},
            json={'username':'fixture','password':'fixture'})
        self.assertEqual(response.status, 200)
        cookie = response.cookies[COOKIE]
        self.assertEqual(cookie['path'], PREFIX)
        self.assertTrue(cookie['httponly'])
        self.assertEqual(cookie['samesite'], 'Strict')
        self.assertFalse(cookie['secure'])
        self.assertTrue(self.bridge.valid_session(cookie.value))
        response = await self.client.post('/api/tv-auth/login', json={'username':'fixture','password':'fixture'})
        self.assertEqual(response.cookies[COOKIE]['path'], '/')

    async def test_nas_identity_headers_do_not_authorize_tcp(self):
        headers = {'Host':'192.168.1.120:8084', 'X-Trim-User':'admin',
                   'X-Trim-User-Id':'1000', 'X-Forwarded-For':'192.168.1.2'}
        for path in ('/api/queue', PREFIX+'/api/queue', PREFIX+'/ws', PREFIX+'/stream/1'):
            self.assertEqual((await self.client.get(path, headers=headers)).status, 401)
        response = await self.client.get(PREFIX+'/api/tv-auth/session', headers=headers)
        self.assertFalse((await response.json())['authed'])
        headers.update(self.auth, Origin='https://public.example', **{'X-Forwarded-Host':'public.example'})
        self.assertEqual((await self.client.post(PREFIX+'/api/queue', headers=headers, json={})).status, 403)

    async def test_source_and_scoring_management_still_require_service_session(self):
        self.bridge.is_local = lambda req: True
        for path in ('/api/v2/sources/music/logout', '/api/admin/scoring/pause'):
            response = await self.client.post(PREFIX+path, json={})
            self.assertEqual(response.status, 401)
        self.bridge.scores.control = AsyncMock(return_value={'paused':True})
        response = await self.client.post(PREFIX+'/api/admin/scoring/pause', headers=self.auth, json={})
        self.assertEqual(response.status, 200)
        self.bridge.scores.control.assert_awaited_once_with('pause', None)
        response = await self.client.post(PREFIX+'/api/admin/scoring/cancel', headers=self.auth, json={'songId':True})
        self.assertEqual(response.status, 400)
        response = await self.client.get(PREFIX+'/api/admin/scoring/pause', headers=self.auth)
        self.assertEqual(response.status, 405)
        response = await self.client.get(PREFIX+'/api/v2/sources', headers=self.auth)
        self.assertTrue((await response.json())['admin'])

    async def test_nas_user_and_admin_are_different_at_source_and_scoring_endpoints(self):
        self.config.update(gateway_socket='/tmp/test.sock', gateway_sso=True)
        self.bridge.scores.control = AsyncMock(return_value={'paused': True})
        with (patch('fnos_gateway.unix_request', return_value=True),
              patch('fnos_gateway.trusted_gateway_peer', return_value=True)):
            headers = {'X-Trim-Userid':'1000','X-Trim-Isadmin':'false'}
            response = await self.client.get(PREFIX+'/api/tv-auth/session', headers=headers)
            status = await response.json()
            self.assertTrue(status['authed'])
            self.assertTrue(status['gatewayAuthenticated'])
            self.assertFalse(status['admin'])
            response = await self.client.get(PREFIX+'/api/v2/sources', headers=headers)
            self.assertFalse((await response.json())['admin'])
            for path in ('/api/v2/sources/music/logout', '/api/admin/scoring/pause'):
                self.assertEqual((await self.client.post(PREFIX+path, headers=headers, json={})).status, 401)
            self.bridge.scores.control.assert_not_awaited()
            headers['X-Trim-Isadmin'] = 'true'
            response = await self.client.get(PREFIX+'/api/v2/sources', headers=headers)
            self.assertTrue((await response.json())['admin'])
            for path in ('/api/v2/sources/music/logout', '/api/admin/scoring/pause'):
                self.assertEqual((await self.client.post(PREFIX+path, headers=headers, json={})).status, 200)
            self.bridge.scores.control.assert_awaited_once_with('pause', None)

    async def test_prefixed_api_uses_original_path_and_query(self):
        with self.bridge.db:
            self.bridge.db.execute('INSERT INTO songs VALUES(1,?,?)', ('fixture', json.dumps({'title':'A & B', 'artist':'Fixture'})))
            self.bridge.db.execute("INSERT INTO current_guids VALUES('fixture')")
        self.bridge.refresh = AsyncMock()
        response = await self.client.get(PREFIX+'/api/songs?q=A%20%26%20B&pageSize=1', headers=self.auth)
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())['total'], 1)
        response = await self.client.post(PREFIX+'/api/queue', headers=self.auth, json={'song_id':1})
        self.assertEqual(response.status, 200)
        response = await self.client.get('/api/queue', headers=self.auth)
        self.assertEqual((await response.json())[0]['song_id'], 1)
        response = await self.client.get(PREFIX+'/api/v2/score-cache/status', headers=self.auth)
        self.assertEqual(response.status, 200)

    async def test_gateway_missing_host_port_requires_user_and_origin_bound_proof(self):
        self.config.update(gateway_socket='/tmp/test.sock', gateway_sso=True)
        self.bridge.scores.control = AsyncMock(return_value={'paused': True})
        origin = 'http://nas.example:5666'
        identity = {'Host':'nas.example', 'X-Trim-Userid':'1000', 'X-Trim-Isadmin':'true'}
        with (patch('fnos_gateway.unix_request', return_value=True),
              patch('fnos_gateway.trusted_gateway_peer', return_value=True)):
            # Reproduces the real fnOS proxy: Host uses $host and drops :5666.
            self.assertEqual((await self.client.get(PREFIX+'/ws', headers=dict(identity, Origin=origin))).status, 403)
            response = await self.client.get(PREFIX+'/api/tv-auth/session',
                headers=dict(identity, **{'X-Karaoke-Origin': origin}))
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers['Cache-Control'], 'no-store')
            self.assertNotIn('Access-Control-Allow-Origin', response.headers)
            data = await response.json()
            proof = data['gatewayProof']
            self.assertNotIn(proof, str(response.url))
            self.assertNotIn(self.config['session_key'], proof)
            async with self.client.ws_connect(PREFIX+'/ws', headers=dict(identity, Origin=origin),
                protocols=['feiniu-karaoke', 'fnos-proof.'+proof]) as ws:
                self.assertEqual(ws.protocol, 'feiniu-karaoke')
                self.assertEqual((await ws.receive_json())['type'], 'queue')
            headers = dict(identity, Origin=origin, **{'X-Karaoke-Proof': proof})
            response = await self.client.post(PREFIX+'/api/admin/scoring/pause', headers=headers, json={})
            self.assertEqual(response.status, 200)
            self.bridge.scores.control.assert_awaited_once_with('pause', None)
            for changes in ({'Origin':'http://nas.example:9999'}, {'Origin':'https://evil.example'},
                            {'X-Trim-Userid':'1001'}, {'Host':'nas.example:9999'},
                            {'X-Karaoke-Proof':proof+'broken'}):
                response = await self.client.post(PREFIX+'/api/admin/scoring/pause',
                    headers=dict(headers, **changes), json={})
                self.assertEqual(response.status, 403)
            with patch('fnos_gateway.time.time', return_value=time.time()+121):
                response = await self.client.post(PREFIX+'/api/admin/scoring/pause', headers=headers, json={})
                self.assertEqual(response.status, 403)
            self.bridge.scores.control.assert_awaited_once()
            foreign = await self.client.get(PREFIX+'/api/tv-auth/session',
                headers=dict(identity, **{'X-Karaoke-Origin':'https://evil.example'}))
            self.assertNotIn('gatewayProof', await foreign.json())
            preflight = await self.client.options(PREFIX+'/api/tv-auth/session',
                headers=dict(identity, Origin='http://nas.example:9999',
                             **{'Access-Control-Request-Method':'GET','Access-Control-Request-Headers':'X-Karaoke-Origin'}))
            self.assertEqual(preflight.status,403)
            self.assertNotIn('Access-Control-Allow-Origin',preflight.headers)
        # Even a valid ticket plus an ordinary service session cannot grant the
        # missing-port exception on direct TCP, where forwarding is untrusted.
        response = await self.client.post(PREFIX+'/api/admin/scoring/pause',
            headers=dict(headers, **self.auth), json={})
        self.assertEqual(response.status, 403)

    async def test_public_module_assets_load_when_gateway_host_omits_browser_port(self):
        self.config.update(gateway_socket='/tmp/test.sock', gateway_sso=True)
        root = self.folder / 'module-server'
        root.mkdir()
        modules = ('webapp.js', 'web-player.js', 'web-audio.js')
        for name in modules:
            (root / name).write_text('export const fixture = true;')
        # These private files must never become eligible static resources.
        (root / 'config.json').write_text('synthetic-private-config')
        (root / 'private.js').write_text('synthetic-private-script')
        identity = {'Host': 'nas.example', 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true'}
        with (patch('fnos_gateway.unix_request', return_value=True),
              patch('fnos_gateway.trusted_gateway_peer', return_value=True),
              patch('server.__file__', str(root / 'server.py'))):
            headers = dict(identity, Origin='http://nas.example:5666')
            for name in modules:
                for method in ('GET', 'HEAD'):
                    with self.subTest(module=name, method=method):
                        response = await self.client.request(method, PREFIX + '/player-assets/' + name,
                                                             headers=headers)
                        self.assertEqual(response.status, 200)
                        self.assertEqual(response.headers['Content-Type'], 'text/javascript')
                        self.assertEqual(response.headers['X-Content-Type-Options'], 'nosniff')
                        self.assertNotIn('Access-Control-Allow-Origin', response.headers)
                        self.assertEqual(await response.text(), '' if method == 'HEAD' else
                                         'export const fixture = true;')
            # Public assets remain readable without a service cookie/SSO,
            # including classic-script loading; no cross-origin read permission
            # or account access is granted by this static response.
            response = await self.client.get(PREFIX + '/player-assets/webapp.js',
                headers={'Host': 'nas.example', 'Origin': 'https://evil.example'})
            self.assertEqual(response.status, 200)
            self.assertNotIn('Access-Control-Allow-Origin', response.headers)
            for name in ('config.json', 'private.js', 'state.db', 'vendor/config.json'):
                response = await self.client.get(PREFIX + '/player-assets/' + name, headers=headers)
                self.assertEqual(response.status, 404)
                body = await response.text()
                self.assertNotIn('synthetic-private', body)
            for name in ('%2e%2e/config.json', 'vendor/%2e%2e/%2e%2e/config.json',
                         'vendor%5c..%5cconfig.json', 'C%3A/config.json'):
                response = await self.client.get(PREFIX + '/player-assets/' + name, headers=headers)
                self.assertIn(response.status, (403, 404))
                self.assertNotIn('synthetic-private', await response.text())

    async def test_public_assets_revalidate_and_changed_content_is_not_stale(self):
        root = self.folder / 'cached-assets'
        root.mkdir()
        module = root / 'webapp.js'
        module.write_text('export const revision = 1;')
        path = PREFIX + '/player-assets/webapp.js'
        with patch('server.__file__', str(root / 'server.py')):
            response = await self.client.get(path)
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers['Cache-Control'], 'public, max-age=0, must-revalidate')
            etag = response.headers['ETag']
            self.assertEqual(await response.text(), 'export const revision = 1;')
            response = await self.client.get(path, headers={'If-None-Match': etag})
            self.assertEqual(response.status, 304)
            self.assertEqual(await response.read(), b'')
            self.assertEqual(response.headers['Cache-Control'], 'public, max-age=0, must-revalidate')
            before = module.stat()
            module.write_text('export const revision = 2;')
            os.utime(module, ns=(before.st_atime_ns, before.st_mtime_ns + 1_000_000_000))
            response = await self.client.get(path, headers={'If-None-Match': etag})
            self.assertEqual(response.status, 200)
            self.assertNotEqual(response.headers['ETag'], etag)
            self.assertEqual(await response.text(), 'export const revision = 2;')
        # Account-specific data must not inherit the public asset cache policy.
        response = await self.client.get(PREFIX + '/api/web/context', headers=self.auth)
        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers['Cache-Control'], 'no-store')

    async def test_static_module_exception_never_grants_api_ws_private_media_or_writes(self):
        self.config.update(gateway_socket='/tmp/test.sock', gateway_sso=True)
        self.bridge.scores.control = AsyncMock(return_value={'paused': True})
        identity = {'Host': 'nas.example', 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true'}
        protected = (
            ('GET', '/api/web/context'), ('HEAD', '/api/queue'),
            ('GET', '/api/v2/sources'), ('GET', '/api/tv-auth/session'),
            ('GET', '/ws'), ('GET', '/stream/1'), ('GET', '/mv/1/master.m3u8'),
            ('GET', '/hls/1/master.m3u8'), ('POST', '/api/admin/scoring/pause'),
            ('POST', '/api/tv-auth/login'), ('POST', '/player-assets/webapp.js'),
            ('OPTIONS', '/player-assets/webapp.js'), ('PUT', '/player-assets/webapp.js'),
            ('PATCH', '/player-assets/webapp.js'), ('DELETE', '/player-assets/webapp.js'),
            ('GET', '/player-assets-other/webapp.js'),
        )
        with (patch('fnos_gateway.unix_request', return_value=True),
              patch('fnos_gateway.trusted_gateway_peer', return_value=True)):
            for origin in ('http://nas.example:5666', 'https://evil.example'):
                for method, path in protected:
                    with self.subTest(origin=origin, method=method, path=path):
                        response = await self.client.request(method, PREFIX + path,
                                                             headers=dict(identity, Origin=origin))
                        self.assertEqual(response.status, 403)
                        self.assertNotIn('Access-Control-Allow-Origin', response.headers)
            self.bridge.scores.control.assert_not_awaited()

    async def test_prefixed_media_ranges_hls_and_descriptor(self):
        root = self.folder/'videos'
        root.mkdir()
        media = root/'song.mp4'
        media.write_bytes(b'0123456789')
        self.bridge.videos.roots = [root]
        with self.bridge.db:
            self.bridge.db.execute('INSERT INTO songs VALUES(1,?,?)', ('video:fixture', json.dumps({'title':'Video', 'media_kind':'mv'})))
            self.bridge.db.execute('INSERT INTO video_sources VALUES(?,?,?,?)', ('video:fixture', str(media), '0', '{}'))
        response = await self.client.get(PREFIX+'/stream/1', headers=dict(self.auth, Range='bytes=2-4'))
        self.assertEqual(response.status, 206)
        self.assertEqual(await response.read(), b'234')
        self.bridge.video_playback.descriptor = AsyncMock(return_value={'playUrl':'/mv/1/master.m3u8'})
        response = await self.client.get(PREFIX+'/api/v2/playback/1', headers=self.auth)
        self.assertEqual((await response.json())['playUrl'], PREFIX+'/mv/1/master.m3u8')
        hls = self.folder/'mv'
        hls.mkdir()
        (hls/'complete').touch()
        (hls/'master.m3u8').write_text('#EXTM3U\nindex_video.m3u8\n')
        (hls/'segment_video_00000.ts').write_bytes(b'video-fixture')
        self.bridge.video_playback.folder = lambda sid: (media, hls)
        response = await self.client.get(PREFIX+'/mv/1/master.m3u8', headers=self.auth)
        self.assertEqual(response.status, 200)
        self.assertIn('index_video.m3u8', await response.text())
        response = await self.client.get(PREFIX+'/mv/1/segment_video_00000.ts', headers=self.auth)
        self.assertEqual(await response.read(), b'video-fixture')
        for path in ('/mv/nope/master.m3u8', '/mv/1/complete', '/hls/1/config.json'):
            self.assertEqual((await self.client.get(PREFIX+path, headers=self.auth)).status, 404)

    async def test_websocket_uses_shared_state_and_only_authorized_playback_hook(self):
        self.bridge.scores.note_playback = Mock()
        socket = await self.client.ws_connect(PREFIX+'/ws', headers=self.auth)
        self.assertEqual((await socket.receive_json())['type'], 'queue')
        await socket.receive_json()
        await socket.receive_json()
        await socket.send_json({'type':'progress','paused':False})
        await socket.send_json({'type':'player_claim','deviceId':'fixture-player'})
        await socket.receive_json()
        await socket.receive_json()
        self.bridge.scores.note_playback.assert_not_called()
        await socket.send_json({'type':'progress','paused':False})
        self.assertEqual((await socket.receive_json())['type'], 'progress')
        self.bridge.scores.note_playback.assert_called_once_with({'type':'progress','paused':False,'_playbackDevice':'fixture-player'})
        await socket.close()


class GatewayTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_forwarded_authority_and_scheme_only_on_unix_transport(self):
        for family, trusted in ((socket.AF_INET, False), (getattr(socket, 'AF_UNIX', -1), True)):
            transport = Mock()
            transport.get_extra_info.return_value = SimpleNamespace(family=family,
                getsockopt=lambda *args: struct.pack('3i', 123, 0, 0))
            req = make_mocked_request('GET', PREFIX+'/api/app/status?a=1%26b=2',
                headers={'Host':'localhost','X-Forwarded-Host':'nas.example','X-Forwarded-Proto':'https'}, transport=transport)
            observed = []
            async def handler(request):
                observed.append(request)
                return web.Response(text='ok')
            with (patch.object(socket, 'AF_UNIX', getattr(socket, 'AF_UNIX', -1), create=True),
                  patch.object(socket, 'SO_PEERCRED', getattr(socket, 'SO_PEERCRED', -1), create=True)):
                await gateway_middleware({'base_path':PREFIX,'gateway_socket':'/tmp/fixture.sock'})(req, handler)
            result = observed[0]
            self.assertEqual(result.path, '/api/app/status')
            self.assertEqual(result.query['a'], '1&b=2')
            self.assertEqual(result['fnos_gateway'], trusted)
            self.assertEqual(result.host, 'nas.example' if trusted else 'localhost')
            self.assertEqual(result.secure, trusted)
            self.assertEqual(scoped_url(result, '/stream/1'), PREFIX+'/stream/1')
            self.assertEqual(scoped_url(result, PREFIX+'/stream/1'), PREFIX+'/stream/1')
            self.assertEqual(scoped_url(result, 'https://other.example/file'), 'https://other.example/file')

    async def test_nas_sso_requires_enabled_feature_trusted_unix_peer_and_userid(self):
        unix_family = getattr(socket, 'AF_UNIX', -1)
        cases = [
            (True, unix_family, 0, '1000', 'true', True, True),
            (True, unix_family, 33, '1001', 'false', True, False),
            (True, unix_family, 1000, '1000', 'true', False, False),
            (True, socket.AF_INET, 0, '1000', 'true', False, False),
            (True, unix_family, 0, '', 'true', False, False),
            (True, unix_family, 0, 'not-a-user', 'true', False, False),
            (False, unix_family, 0, '1000', 'true', False, False),
        ]
        bridge = SimpleNamespace(is_local=lambda req: False, valid_session=lambda cookie: False)
        for enabled, family, uid, userid, admin, authed, privileged in cases:
            with self.subTest(enabled=enabled, family=family, uid=uid, userid=userid, admin=admin):
                transport = Mock()
                transport.get_extra_info.return_value = SimpleNamespace(family=family,
                    getsockopt=lambda *args: struct.pack('3i', 123, uid, 1000))
                req = make_mocked_request('GET', PREFIX+'/api/tv-auth/session', transport=transport,
                    headers={'Host':'localhost','X-Trim-Userid':userid,'X-Trim-Isadmin':admin,
                             'X-Forwarded-Host':'nas.example','X-Forwarded-Proto':'https'})
                observed = []
                async def handler(request):
                    observed.append(request)
                    return web.Response()
                with (patch.object(socket, 'AF_UNIX', unix_family, create=True),
                      patch.object(socket, 'SO_PEERCRED', getattr(socket, 'SO_PEERCRED', -1), create=True),
                      patch('fnos_gateway.gateway_peer_uids', return_value={0,33})):
                    await gateway_middleware({'base_path':PREFIX,'gateway_socket':'/tmp/app.sock',
                                              'gateway_sso':enabled})(req, handler)
                result = observed[0]
                self.assertEqual(Bridge.authed(bridge, result), authed)
                self.assertEqual(Bridge.is_admin_session(bridge, result), privileged)
                if uid == 1000 or family == socket.AF_INET:
                    self.assertEqual(result.host, 'localhost')
                    self.assertFalse(result.secure)

    def test_invalid_prefixes_fail_closed(self):
        for value in ('/', 'app/karaoke', '/app/karaoke/', '/app/../etc', '/app/a?x=1', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                base_path({'base_path':value})
        with self.assertRaises(ValueError):
            base_path({'gateway_socket':'/tmp/fixture.sock'})

    async def test_runner_tcp_remains_usable_without_gateway(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            config = fixture_config(folder, bind='127.0.0.1', port=0)
            app = create_app(config, folder/'state.db')
            listeners = await AppListeners(app, config).start()
            try:
                address = listeners.runner.addresses[0]
                async with ClientSession() as client:
                    async with client.get(f'http://127.0.0.1:{address[1]}/api/tv-auth/session') as response:
                        self.assertEqual(response.status, 200)
                        self.assertFalse((await response.json())['authed'])
            finally:
                await listeners.close()

    @unittest.skipIf(sys.platform == 'win32', 'asyncio UNIX listeners require the Linux fnOS runtime')
    async def test_real_unix_gateway_and_tcp_share_app_without_header_auth(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path = folder/'app.sock'
            config = fixture_config(folder, gateway_socket=str(path), bind='127.0.0.1', port=0)
            app = create_app(config, folder/'state.db')
            listeners = await AppListeners(app, config).start()
            try:
                self.assertEqual(path.stat().st_mode & 0o777, 0o666)
                async with ClientSession(connector=UnixConnector(path=str(path))) as client:
                    headers = {'X-Trim-User':'admin','X-Forwarded-Host':'nas.example','X-Forwarded-Proto':'https'}
                    async with client.get('http://localhost'+PREFIX+'/api/app/status', headers=headers) as response:
                        self.assertEqual(response.status, 401)
                    async with client.post('http://localhost'+PREFIX+'/api/tv-auth/login', headers=headers,
                        json={'username':'fixture','password':'fixture'}) as response:
                        self.assertEqual(response.status, 200)
                        self.assertEqual(bool(response.cookies[COOKIE]['secure']), os.getuid() in gateway_peer_uids())
                        headers['Cookie'] = COOKIE+'='+response.cookies[COOKIE].value
                    async with client.get('http://localhost'+PREFIX+'/api/app/status', headers=headers) as response:
                        self.assertTrue((await response.json())['gateway'])
                    async with client.ws_connect('http://localhost'+PREFIX+'/ws', headers=headers) as ws:
                        self.assertEqual((await ws.receive_json())['type'], 'queue')
            finally:
                await listeners.close()
            self.assertFalse(path.exists())

    @unittest.skipIf(sys.platform == 'win32', 'real UNIX peer credentials require Linux')
    async def test_real_unix_origin_proof_upgrades_ws_and_authorizes_admin_post(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path = folder/'app.sock'
            config = fixture_config(folder, gateway_socket=str(path), gateway_sso=True, tcp_enabled=False)
            app = create_app(config, folder/'state.db')
            app['bridge'].scores.control = AsyncMock(return_value={'paused':True})
            listeners = await AppListeners(app, config).start()
            try:
                # Only this isolated application's peer allowlist is changed;
                # the transport and SO_PEERCRED lookup are the actual kernel path.
                with patch('fnos_gateway.gateway_peer_uids', return_value={os.getuid()}):
                    async with ClientSession(connector=UnixConnector(path=str(path))) as client:
                        identity = {'X-Trim-Userid':'1000','X-Trim-Isadmin':'true'}
                        origin = 'http://localhost:5666'
                        async with client.get('http://localhost'+PREFIX+'/api/tv-auth/session',
                            headers=dict(identity, **{'X-Karaoke-Origin':origin})) as response:
                            self.assertEqual(response.status,200)
                            proof = (await response.json())['gatewayProof']
                        async with client.ws_connect('http://localhost'+PREFIX+'/ws',
                            headers=dict(identity, Origin=origin), protocols=['feiniu-karaoke','fnos-proof.'+proof]) as ws:
                            self.assertEqual(ws.protocol,'feiniu-karaoke')
                            self.assertEqual((await ws.receive_json())['type'],'queue')
                        async with client.post('http://localhost'+PREFIX+'/api/admin/scoring/pause',
                            headers=dict(identity, Origin=origin, **{'X-Karaoke-Proof':proof}),json={}) as response:
                            self.assertEqual(response.status,200)
                        app['bridge'].scores.control.assert_awaited_once_with('pause',None)
            finally:
                await listeners.close()


if __name__ == '__main__':
    unittest.main()
