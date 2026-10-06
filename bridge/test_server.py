import hashlib
import asyncio
import ipaddress
import json
import os
import time
import secrets
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock
from aiohttp.test_utils import TestClient, TestServer
from server import COOKIE, create_app, local_request


class TrustTests(unittest.TestCase):
    def test_only_explicit_home_subnet_and_literal_host(self):
        nets = [ipaddress.ip_network('192.168.1.0/24')]
        self.assertTrue(local_request('192.168.1.62', '192.168.1.120:8084', {}, nets))
        for peer, host, headers in [
            ('203.0.113.5', '192.168.1.120:8084', {}),
            ('10.0.0.4', '192.168.1.120:8084', {}),
            ('192.168.1.62', 'example.com', {}),
            ('192.168.1.62', '192.168.1.120:8084', {'X-Forwarded-For':'192.168.1.62'}),
            ('192.168.1.62', '192.168.1.120:8084', {'Forwarded':'for=192.168.1.62'}),
            ('127.0.0.1', '192.168.1.120:8084', {}),
        ]:
            with self.subTest(peer=peer, host=host, headers=headers):
                self.assertFalse(local_request(peer, host, headers, nets))


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_phone_target_websocket_isolated_from_tv_queue_and_switches_back(self):
        async def until(ws, kind):
            for _ in range(20):
                value = await asyncio.wait_for(ws.receive_json(), 2)
                if value.get('type') == kind:
                    return value
            self.fail('Missing socket message: '+kind)
        phone = await self.client.ws_connect('/ws', headers=self.headers)
        tablet = await self.client.ws_connect('/ws', headers=self.headers)
        await until(phone, 'playback_devices')
        await until(tablet, 'playback_devices')
        await phone.send_json({'type':'role_announce','role':'controller','deviceId':'phone-one','independentPlayer':True})
        self.assertEqual('phone-one', (await until(tablet,'playback_devices'))['phones'][0]['id'])
        await tablet.send_json({'type':'target_select','targetDeviceId':'phone-one'})
        self.assertTrue((await until(tablet,'target_ack'))['online'])
        await until(tablet,'queue')
        await tablet.send_json({'type':'control','action':'enqueue','song_id':2,'commandId':'fixture'})
        command=await until(phone,'phone_control')
        self.assertEqual(2,command['song']['song_id'])
        self.assertEqual([],self.b.queue())
        await phone.send_json({'type':'phone_state','queue':[{'song_id':2,'queue_id':200}], 'progress':{'queueId':200,'paused':False}})
        self.assertEqual(200,(await until(tablet,'queue'))['data'][0]['queue_id'])
        self.assertEqual('phone-one',(await until(tablet,'progress'))['targetDeviceId'])
        await tablet.send_json({'type':'target_select','targetDeviceId':''})
        self.assertEqual('',(await until(tablet,'target_ack'))['targetDeviceId'])
        self.assertEqual([],(await until(tablet,'queue'))['data'])
        await phone.close();await tablet.close()

    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        salt = secrets.token_bytes(16)
        self.cfg = dict(catalog_sync_enabled=False,score_generation_enabled=True,score_prewarm=False,lan_networks=['192.168.1.0/24'], music_url='http://127.0.0.1:9/music',
                        music_token='synthetic-token', username='tester', password_salt=salt.hex(),
                        password_hash=hashlib.pbkdf2_hmac('sha256', b'fixture-password', salt, 200000).hex(),
                        session_key=secrets.token_hex(32))
        self.app = create_app(self.cfg, Path(self.temp.name)/'state.db')
        self.b = self.app['bridge']
        with self.b.db:
            for i in range(1, 4):
                self.b.db.execute('INSERT INTO songs VALUES(?,?,?)', (i, 'fixture'+str(i), json.dumps({'title': 'Song'+str(i), 'artist':'Fixture'})))
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.headers = {'Cookie': COOKIE+'='+self.b.session()}

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    async def test_external_requires_login_for_api_media_websocket(self):
        for path in ['/api/songs', '/api/queue', '/api/lyrics/1', '/api/pitch/1', '/api/score/1', '/api/cover/1', '/api/accompaniment/1', '/stream/1', '/hls/1/master.m3u8', '/hls/1/segment00000.ts', '/ws']:
            self.assertEqual((await self.client.get(path)).status, 401)
        d = await (await self.client.get('/api/tv-auth/session')).json()
        self.assertFalse(d['authed'])
        self.assertFalse(d['lan'])

    async def test_lan_playback_bypass_does_not_authorize_source_management(self):
        self.b.is_local = lambda req: True
        for action in ('music/login', 'music/logout', 'music/sync', 'video/login', 'video/logout', 'video/libraries'):
            response = await self.client.post('/api/v2/sources/'+action, json={})
            self.assertEqual(response.status, 401, action)
        public = await (await self.client.get('/api/v2/sources')).json()
        self.assertFalse(public['admin'])
        self.assertNotIn('synthetic-token', json.dumps(public))
        self.assertNotIn('password_hash', json.dumps(public))
        authorized = await (await self.client.get('/api/v2/sources', headers=self.headers)).json()
        self.assertTrue(authorized['admin'])

    async def test_health_survives_unconfigured_music_source(self):
        self.b.sources.music.logout()
        response = await self.client.get('/api/v2/health', headers=self.headers)
        self.assertEqual(response.status, 200)
        self.assertTrue((await response.json())['ok'])

    async def test_login_and_persistent_session_cookie(self):
        bad = await self.client.post('/api/tv-auth/login', json={'username':'tester','password':'wrong'})
        self.assertEqual(bad.status, 401)
        good = await self.client.post('/api/tv-auth/login', json={'username':'tester','password':'fixture-password'})
        self.assertEqual(good.status, 200)
        self.assertEqual(good.cookies[COOKIE]['max-age'], '2592000')
        cookie = good.cookies[COOKIE].value
        self.assertTrue(self.b.valid_session(cookie))
        self.assertFalse(self.b.valid_session(cookie[:-1] + ('a' if cookie[-1]!='a' else 'b')))
        response = await self.client.get('/api/queue', headers={'Cookie':COOKIE+'='+cookie})
        self.assertEqual(response.status, 200)

    async def test_spoofed_forwarded_address_not_trusted(self):
        r = await self.client.get('/api/queue', headers={'X-Forwarded-For':'192.168.1.62','Host':'192.168.1.120:8084'})
        self.assertEqual(r.status, 401)

    async def test_cross_origin_mutations_denied(self):
        r = await self.client.post('/api/queue', headers=dict(self.headers, Origin='https://evil.example'), json={'song_id':1})
        self.assertEqual(r.status, 403)

    async def test_get_requests_cannot_mutate_queue(self):
        for path in ['/api/queue/1/top','/api/queue/next','/api/queue/1','/api/favorites/1']:
            self.assertEqual((await self.client.get(path, headers=self.headers)).status, 405)

    async def test_preferred_synced_lyrics_and_cache(self):
        self.b.fn=AsyncMock(return_value={'preferred':'wanted','list':[
            {'guid':'other','content':'plain','isLRC':False},
            {'guid':'wanted','content':'[00:01.23]Fixture','isLRC':True,'offset':100}]})
        for _ in range(2):
            d=await (await self.client.get('/api/lyrics/1',headers=self.headers)).json()
            self.assertTrue(d['synced']);self.assertEqual(d['offset'],100)
            self.assertEqual(d['content'],'[00:01.23]Fixture')
        self.b.fn.assert_awaited_once_with('lyric/list',{'trackGUID':'fixture1'})

    async def test_no_cover_is_explicit_and_does_not_fetch_arbitrary_url(self):
        self.b.fn=AsyncMock(return_value={'track':{}})
        r=await self.client.get('/api/cover/1',headers=self.headers)
        self.assertEqual(r.status,404)
        self.b.fn.assert_awaited_once_with('track/metadata',{'guid':'fixture1'})

    async def test_media_filter_before_pagination_and_video_range_stream(self):
        root=Path(self.temp.name)/'videos';root.mkdir()
        video=root/'fixture.mp4';video.write_bytes(b'0123456789')
        self.b.videos.roots=[root]
        self.b.refresh=AsyncMock()
        self.b.db.execute('CREATE TEMP TABLE current_guids AS SELECT guid FROM songs')
        self.b.db.execute("UPDATE songs SET guid='video:fixture',data=? WHERE id=2",
                          (json.dumps({'title':'Video','artist':'Fixture','media_kind':'mv'}),))
        self.b.db.execute("UPDATE current_guids SET guid='video:fixture' WHERE guid='fixture2'")
        self.b.db.execute('INSERT INTO video_sources VALUES(?,?,?,?)',('video:fixture',str(video),'0','{}'))
        response=await self.client.get('/api/songs?media_kind=mv&pageSize=1',headers=self.headers)
        result=await response.json()
        self.assertEqual(result['total'],1);self.assertEqual(result['items'][0]['id'],2)
        self.assertNotIn(str(root),json.dumps(result))
        response=await self.client.get('/stream/2',headers=dict(self.headers,Range='bytes=2-4'))
        self.assertEqual(response.status,206);self.assertEqual(await response.read(),b'234')
        self.assertEqual((await self.client.get('/stream/2')).status,401)
        video.unlink()
        self.assertEqual((await self.client.get('/stream/2',headers=self.headers)).status,404)

    async def test_score_unavailable_without_worker_and_accompaniment_not_premature(self):
        self.b.scores.python=Path(self.temp.name)/'absent-python'
        d=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertEqual(d['status'],'error')
        self.assertEqual((await self.client.get('/api/accompaniment/1',headers=self.headers)).status,404)

    async def test_generation_disabled_blocks_selection_polling_and_prewarm(self):
        self.b.cfg['score_generation_enabled']=False
        self.b.cfg['score_prewarm']=True  # A stale prewarm setting cannot override the switch.
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        self.b.refresh=AsyncMock()
        response=await self.client.post('/api/queue',headers=self.headers,json={'song_id':2})
        self.assertEqual(response.status,200)
        for _ in range(3):
            data=await (await self.client.get('/api/score/2',headers=self.headers)).json()
            self.assertEqual(data['status'],'unavailable')
        await self.b.scores.prewarm_once()
        self.b.scores.run.assert_not_awaited()
        self.assertEqual(self.b.scores.jobs,{})
        self.assertFalse(self.b.scores.folder(2).exists())
        self.b.db.execute('CREATE TEMP TABLE current_guids AS SELECT guid FROM songs')
        state=await self.b.scores.library_status()
        self.assertFalse(state['enabled']);self.assertFalse(state['generationEnabled'])
        self.assertEqual(state['workerThreads'],0)

    async def test_generation_defaults_off_and_reuses_legacy_resources(self):
        self.b.cfg.pop('score_generation_enabled')
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','notes':[69,None]}))
        (folder/'accompaniment.m4a').write_bytes(b'fixture')
        data=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertEqual(data['notes'],[69,None]);self.assertTrue(data['accompaniment'])
        self.assertFalse(data['upgrading']);self.b.scores.run.assert_not_awaited()
        self.assertEqual((await self.client.get('/api/accompaniment/1',headers=self.headers)).status,200)

    async def test_provider_login_hides_secrets_and_rejects_foreign_mutations(self):
        self.b.online_tracks.call=AsyncMock(return_value={'status':'saved','token':'must-not-leak','userid':'hidden'})
        url='/api/sources/kugou/login'
        self.assertEqual((await self.client.post(url)).status,401)
        self.assertEqual((await self.client.get(url,headers=self.headers)).status,405)
        self.assertEqual((await self.client.post(url,headers=dict(self.headers,Origin='https://evil.example'))).status,403)
        data=await (await self.client.post(url,headers=self.headers)).json()
        self.assertEqual(data,{'status':'saved'})
        self.b.online_tracks.call.assert_awaited_once_with('login')
        self.assertEqual((await self.client.post('/api/sources/kugou/search',headers=self.headers)).status,404)

    async def test_cached_online_accompaniment_does_not_require_a_score(self):
        self.b.cfg['score_generation_enabled']=False
        self.b.scores.root=Path(self.temp.name)/'score'
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'accompaniment.m4a').write_bytes(b'fixture')
        self.b.online_tracks.request=unittest.mock.Mock()
        data=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertEqual(data['status'],'unavailable');self.assertTrue(data['accompaniment'])
        self.b.online_tracks.request.assert_not_called()
        self.assertEqual((await self.client.get('/api/accompaniment/1',headers=self.headers)).status,200)

    async def test_score_cache_returns_only_prepared_vocal_reference(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','method':'vocal-fixture','notes':[69,None]}))
        d=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertEqual(d['method'],'vocal-fixture')
        self.assertEqual(d['notes'],[69,None])

    async def test_ready_local_score_does_not_wait_for_shared_fingerprint_during_playback(self):
        self.b.cfg['score_generation_enabled']=False
        self.b.scores.root=Path(self.temp.name)/'score'
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','referenceVersion':4,'notes':[69,None]}))
        self.b.shared_scores.root=Path(self.temp.name)/'shared'
        (self.b.shared_scores.root/('f'*64)).mkdir(parents=True)
        self.b.scores.note_playback({'paused':False})
        self.b.shared_scores.fingerprint=AsyncMock()
        for _ in range(2):
            response=await self.client.get('/api/score/1',headers=self.headers)
            data=await response.json()
            self.assertEqual(response.status,200)
            self.assertEqual(data['status'],'ready')
            self.assertEqual(data['notes'],[69,None])
        self.b.shared_scores.fingerprint.assert_not_awaited()
        self.assertEqual(self.b.shared_scores.pending,{})

    async def test_score_jobs_coalesce_repeated_requests(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        for _ in range(2):
            d=await (await self.client.get('/api/score/1',headers=self.headers)).json()
            self.assertEqual(d['status'],'queued')
        self.b.scores.run.assert_awaited_once()

    async def test_audio_eviction_preserves_melody_and_reports_no_accompaniment(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.cfg['score_accompaniment_limit_bytes']=0
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','notes':[69], 'accompaniment':True}))
        (folder/'accompaniment.m4a').write_bytes(b'fixture')
        os.utime(folder,(time.time()-7200,)*2)
        self.b.scores.prune(self.b.scores.folder(2))
        self.assertTrue((folder/'melody-v2.json').exists())
        self.assertFalse((folder/'accompaniment.m4a').exists())
        response=await self.client.get('/api/score/1',headers=self.headers)
        data=await response.json()
        self.assertEqual(data['status'],'ready');self.assertFalse(data['accompaniment'])

    async def test_background_prefers_queue_and_reuses_durable_melody(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        self.b.refresh=AsyncMock()
        self.b.db.execute('CREATE TEMP TABLE current_guids AS SELECT guid FROM songs')
        self.b.db.execute('INSERT INTO queue(song_id) VALUES(2)')
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text('{"status":"ready","referenceVersion":4}')
        await self.b.scores.prewarm_once()
        self.assertIn(2,self.b.scores.jobs)
        self.assertIsNone(await self.b.scores.prewarm_once())
        self.assertNotIn(1,self.b.scores.jobs)

    async def test_selecting_song_prepares_reference_without_any_tv_request(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        response=await self.client.post('/api/queue',headers=self.headers,json={'song_id':2})
        self.assertEqual(response.status,200)
        self.assertIn(2,self.b.scores.jobs)
        await self.b.scores.jobs[2]
        self.b.scores.run.assert_awaited_once_with(2,self.b.scores.folder(2))

    async def test_legacy_reference_is_served_while_upgrade_is_queued(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','notes':[69,None]}))
        d=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertTrue(d['upgrading']);self.assertEqual(d['notes'],[69,None])
        await self.b.scores.jobs[1]
        self.b.scores.run.assert_awaited_once()

    async def test_current_reference_is_reused_without_rerunning_analysis(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text(json.dumps({'status':'ready','referenceVersion':4,'notes':[69]}))
        d=await (await self.client.get('/api/score/1',headers=self.headers)).json()
        self.assertFalse(d['upgrading']);self.assertEqual(d['referenceVersion'],4)
        self.b.scores.run.assert_not_awaited()

    async def test_long_song_is_scheduled_and_library_progress_is_durable(self):
        self.b.scores.root=Path(self.temp.name)/'score'
        self.b.scores.python=Path(__file__)
        self.b.scores.run=AsyncMock()
        self.b.refresh=AsyncMock()
        self.b.db.execute('CREATE TEMP TABLE current_guids AS SELECT guid FROM songs')
        self.b.db.execute('UPDATE songs SET data=? WHERE id=2',(json.dumps({'title':'Long','duration':7872}),))
        folder=self.b.scores.folder(1);folder.mkdir(parents=True)
        (folder/'melody-v2.json').write_text('{"status":"ready","referenceVersion":4}')
        response=await self.client.get('/api/score/2',headers=self.headers)
        self.assertEqual(response.status,200)
        await self.b.scores.jobs[2]
        self.b.scores.run.assert_awaited_once()
        data=await (await self.client.get('/api/score-library',headers=self.headers)).json()
        self.assertEqual((data['total'],data['ready'],data['pending']),(3,1,2))
        self.assertEqual(json.loads((self.b.scores.root/'library-progress.json').read_text()),data)
        self.assertEqual((await self.client.get('/api/score-library')).status,401)

    async def test_preparation_failure_does_not_turn_successful_selection_into_error(self):
        self.b.scores.prepare_queue=AsyncMock(side_effect=RuntimeError('synthetic failure'))
        response=await self.client.post('/api/queue',headers=self.headers,json={'song_id':2})
        self.assertEqual(response.status,200)
        self.assertEqual([s['song_id'] for s in self.b.queue()],[2])

    async def test_repeated_song_unique_ids_and_expected_next(self):
        for _ in range(2):
            self.assertEqual((await self.client.post('/api/queue', headers=self.headers, json={'song_id':1})).status, 200)
        rows = self.b.queue()
        self.assertNotEqual(rows[0]['queue_id'], rows[1]['queue_id'])
        for _ in range(2):
            await self.client.post('/api/queue/next', headers=self.headers, json={'expected_queue_id':rows[0]['queue_id']})
        self.assertEqual([s['queue_id'] for s in self.b.queue()], [rows[1]['queue_id']])

    async def test_top_preserves_identity_and_next_follows_order(self):
        for sid in [1,2,3]:
            await self.client.post('/api/queue', headers=self.headers, json={'song_id':sid})
        original = self.b.queue()
        await self.client.post('/api/queue/'+str(original[2]['queue_id'])+'/top', headers=self.headers, json={})
        self.assertEqual([s['song_id'] for s in self.b.queue()], [1,3,2])
        await self.client.post('/api/queue/next', headers=self.headers, json={'expected_queue_id':original[0]['queue_id']})
        self.assertEqual(self.b.queue()[0]['queue_id'], original[2]['queue_id'])

    async def test_device_favorites_separate(self):
        await self.client.post('/api/favorites/1', headers=self.headers, json={'device':'tv-a'})
        a = await (await self.client.get('/api/favorites?device=tv-a', headers=self.headers)).json()
        b = await (await self.client.get('/api/favorites?device=tv-b', headers=self.headers)).json()
        self.assertEqual(len(a),1); self.assertEqual(b,[])

    async def test_websocket_claim_and_queue_sync(self):
        ws = await self.client.ws_connect('/ws', headers=self.headers)
        self.assertEqual((await ws.receive_json())['type'], 'queue')
        self.assertEqual((await ws.receive_json())['type'], 'player_changed')
        self.assertEqual((await ws.receive_json())['type'], 'playback_devices')
        await ws.send_json({'type':'player_claim','deviceId':'fixture-tv','deviceName':'TV'})
        self.assertTrue((await ws.receive_json())['granted'])
        self.assertEqual((await ws.receive_json())['activeDeviceId'],'fixture-tv')
        await self.client.post('/api/queue', headers=self.headers, json={'song_id':2})
        self.assertEqual((await ws.receive_json())['data'][0]['song_id'],2)
        await ws.close()


if __name__ == '__main__':
    unittest.main()
