import asyncio,hashlib,json,sqlite3,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
from shared_scores import SharedScores,METHOD

class SharedTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.media=self.root/'mv';self.media.write_bytes(b'exact recording')
        self.digest=hashlib.sha256(self.media.read_bytes()).hexdigest();db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
        db.execute('CREATE TABLE songs(id INTEGER,guid TEXT,data TEXT)');db.execute("INSERT INTO songs VALUES (1,'video:test','{}')")
        self.b=SimpleNamespace(cfg={},db=db,scores=SimpleNamespace(root=self.root/'scores'),videos=SimpleNamespace(path=lambda _:self.media))
        self.s=SharedScores(self.b);app=web.Application(client_max_size=65536);app.router.add_route('*','/api/{tail:.*}',self.s.api)
        self.client=TestClient(TestServer(app));await self.client.start_server()
        self.data=dict(notes=[60.]*15000,stepMs=40,durationMs=600000,method=METHOD,timelineVersion=2,audioTrack=0,requestedTrack=-1,mediaSha256=self.digest,complete=True)
        self.url='/api/v2/score-cache/'+self.digest+'/-1'
    async def asyncTearDown(self):
        await self.s.close();await self.client.close();self.b.db.close();self.tmp.cleanup()
    async def test_roundtrip_large_body_restart_and_android_endpoint_shape(self):
        r=await self.client.put(self.url,json=dict(self.data,cookie='never persist'));self.assertEqual(r.status,200)
        revision=(await r.json())['revision'];r=await self.client.get(self.url);data=await r.json()
        self.assertNotIn('cookie',data);self.assertEqual(data['status'],'ready');self.assertEqual(data['notes'],self.data['notes'])
        self.assertEqual((await self.s.for_song(1))['status'],'preparing')
        await asyncio.gather(*list(self.s.pending.values()))
        cached=await self.s.for_song(1);self.assertEqual(cached['durationMs'],600000);self.assertEqual(cached['referenceVersion'],5)
        await self.s.close();self.s=SharedScores(self.b)
        self.assertEqual((await self.s.for_song(1))['notes'],self.data['notes'])
        self.media.write_bytes(b'different live version')
        self.assertEqual((await self.s.for_song(1))['status'],'preparing');await asyncio.gather(*list(self.s.pending.values()))
        self.assertIsNone(await self.s.for_song(1))
    async def test_invalid_and_incomplete_never_overwrite(self):
        self.assertEqual((await self.client.put(self.url,json=self.data)).status,200)
        for fields in ({'complete':False},{'requestedTrack':1},{'durationMs':1000},{'notes':[None]*15000},{'timelineVersion':'bad'}):
            r=await self.client.put(self.url,json=dict(self.data,**fields));self.assertEqual(r.status,400)
        self.assertEqual((await (await self.client.get(self.url)).json())['notes'],self.data['notes'])

    async def test_fingerprinting_yields_to_playback_and_is_cancellable(self):
        self.assertEqual((await self.client.put(self.url,json=self.data)).status,200)
        self.b.scores.playback_active=True
        self.assertEqual((await self.s.for_song(1))['status'],'preparing')
        await asyncio.sleep(.01)
        self.assertIsNone(self.b.db.execute('SELECT digest FROM shared_score_media').fetchone())
        await self.s.close()
        self.assertEqual(self.s.pending,{})
        self.b.scores.playback_active=False
        await self.s.for_song(1)
        await asyncio.gather(*list(self.s.pending.values()))
        self.assertEqual((await self.s.for_song(1))['status'],'ready')

if __name__=='__main__':unittest.main()
