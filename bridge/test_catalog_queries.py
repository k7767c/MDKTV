import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
from aiohttp.test_utils import make_mocked_request, TestClient, TestServer
from catalog_queries import CatalogQueries, initials
from catalog_sync import CatalogSync


def database(path=':memory:'):
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    db.executescript('''CREATE TABLE songs(id INTEGER PRIMARY KEY,guid TEXT UNIQUE,data TEXT);
        CREATE TABLE current_guids(guid TEXT PRIMARY KEY);
        CREATE TABLE history(id INTEGER PRIMARY KEY,song_id INTEGER,at REAL);''')
    return db


def insert(db, sid, **data):
    db.execute('INSERT INTO songs VALUES(?,?,?)', (sid,str(sid),json.dumps(data,ensure_ascii=False)))
    db.execute('INSERT INTO current_guids VALUES(?)', (str(sid),))


class QueryTests(unittest.TestCase):
    def setUp(self):
        self.db = database()
        self.queries = CatalogQueries(self.db)
        self.rows = [
            {'title':'红豆','artist':'王菲','media_kind':'audio'},
            {'title':'Straße','artist':'İpek','media_kind':'mv'},
            {'title':'Σίσυφος','artist':'STRASSE','media_kind':'audio'},
            {'title':'你好世界','artist':'甲乙','media_kind':'mv'},
            {'title':'%_Literal','artist':'甲乙','media_kind':'audio'},
        ]
        for sid,data in enumerate(self.rows,1):
            insert(self.db,sid,**data)
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def response(self, url, headers=None):
        return self.queries.response(make_mocked_request('GET',url,headers=headers or {}), 7)

    def test_unicode_casefold_filters_paging_and_id_contract(self):
        from urllib.parse import urlencode
        for q,artist,kind in [('红','王','audio'),('STRASSE','',''),('','i\u0307','mv'),('%_','',''),('界甲','乙','mv'),('σίσυφοσ','','')]:
            expected=[dict(data,id=sid,song_id=sid) for sid,data in enumerate(self.rows,1)
                      if q.casefold() in (data['title']+data['artist']).casefold()
                      and artist.casefold() in data['artist'].casefold() and (not kind or data['media_kind']==kind)]
            result=json.loads(self.response('/api/songs?'+urlencode(dict(q=q,artist=artist,media_kind=kind))).body)
            self.assertEqual(result['items'], expected)
            self.assertEqual(result['total'],len(expected))
            self.assertEqual(result['catalogRevision'],7)
            self.assertEqual(result['catalogToken'],self.queries.token)
        result=json.loads(self.response('/api/songs?page=2&pageSize=2').body)
        self.assertEqual([s['id'] for s in result['items']],[3,4])
        self.assertEqual(result['total'],5)
        self.assertEqual(json.loads(self.response('/api/songs?page=999999999999999999999999999').body)['items'],[])

    def test_artists_and_charts_match_legacy_order_and_decode_only_ranked_rows(self):
        self.db.executemany('INSERT INTO history(song_id,at) VALUES(?,0)',[(4,),(2,),(4,)])
        artists=json.loads(self.response('/api/artists').body)
        self.assertEqual(artists,[{'artist':a, 'initials':initials(a)} for a in sorted(set(row['artist'] for row in self.rows))])
        charts=json.loads(self.response('/api/charts').body)
        self.assertEqual([s['id'] for s in charts],[4,2,1,3,5])
        self.assertEqual([s['play_count'] for s in charts],[2,1,0,0,0])

    def test_conditional_response_cache_and_visibility_invalidation(self):
        first=self.response('/api/songs')
        with patch.object(self.queries,'decode',side_effect=AssertionError('unexpected decoding')):
            self.assertEqual(self.response('/api/songs').body,first.body)
            unchanged=self.response('/api/songs',{'If-None-Match':first.headers['ETag']})
            self.assertEqual(unchanged.status,304)
            self.assertIsNone(unchanged.body)
        self.db.execute("DELETE FROM current_guids WHERE guid='1'")
        changed=self.response('/api/songs',{'If-None-Match':first.headers['ETag']})
        self.assertEqual(changed.status,200)
        self.assertEqual(json.loads(changed.body)['total'],4)
        self.assertNotEqual(changed.headers['ETag'],first.headers['ETag'])
        self.db.execute('UPDATE songs SET data=? WHERE id=2',(json.dumps(dict(self.rows[1],title='new title')),))
        self.assertEqual(json.loads(self.response('/api/songs?q=new').body)['items'][0]['id'],2)

    def test_history_changes_only_invalidate_charts(self):
        songs=self.response('/api/songs')
        charts=self.response('/api/charts')
        self.db.execute('INSERT INTO history(song_id,at) VALUES(5,0)')
        self.assertEqual(self.response('/api/songs',{'If-None-Match':songs.headers['ETag']}).status,304)
        updated=self.response('/api/charts',{'If-None-Match':charts.headers['ETag']})
        self.assertEqual(updated.status,200)
        self.assertEqual(json.loads(updated.body)[0]['id'],5)

    def test_chinese_initials_are_projected_once_and_visibility_filters_remain_in_force(self):
        insert(self.db,6,title='晴天',artist='周杰伦',media_kind='audio')
        self.db.commit()
        with patch('catalog_queries.lazy_pinyin',side_effect=AssertionError('page query must not re-transliterate')):
            result=json.loads(self.response('/api/songs?q=qtzjl').body)
            self.assertEqual([row['id'] for row in result['items']],[6])
            result=json.loads(self.response('/api/artists?q=zjl').body)
            self.assertEqual(result,[{'artist':'周杰伦','initials':'zjl'}])
            self.db.execute("DELETE FROM current_guids WHERE guid='6'")
            self.assertEqual(json.loads(self.response('/api/songs?q=qtzjl').body)['items'],[])

    def test_persistent_token_survives_restart_and_changes_on_new_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=str(Path(tmp)/'catalog.db')
            db=database(path)
            queries=CatalogQueries(db)
            insert(db,1,title='fixture',artist='x')
            db.commit()
            token=queries.token
            db.close()
            db=sqlite3.connect(path)
            db.row_factory=sqlite3.Row
            queries=CatalogQueries(db)
            self.assertEqual(queries.token,token)
            db.close()
            other=database()
            self.assertNotEqual(CatalogQueries(other).token,token)
            other.close()

    def test_large_catalog_page_decoding_is_bounded_and_cache_is_bounded(self):
        with self.db:
            for sid in range(6,10006):
                insert(self.db,sid,title='合成歌曲'+str(sid),artist='合成歌手',media_kind='audio')
        with patch.object(self.queries,'decode',wraps=self.queries.decode) as decode:
            result=json.loads(self.response('/api/songs?page=101&pageSize=30').body)
            self.assertEqual(result['total'],10005)
            self.assertEqual(len(result['items']),30)
            self.assertEqual(decode.call_count,30)
        for page in range(1,41):
            self.response('/api/songs?pageSize=30&page='+str(page))
        self.assertLessEqual(len(self.queries.cache),32)
        self.assertLessEqual(self.queries.cache_bytes,self.queries.CACHE_BYTES)


class SyncTests(unittest.IsolatedAsyncioTestCase):
    async def test_unchanged_scan_keeps_token_and_avoids_database_writes(self):
        db=database()
        b=SimpleNamespace(db=db,cfg={},broadcast=AsyncMock(),
            music_rows=AsyncMock(return_value={'m1':{'title':'one','artist':'artist'}}),
            videos=SimpleNamespace(scan=AsyncMock(return_value={})))
        sync=CatalogSync(b)
        await sync.sync('music')
        token=sync.queries.token
        changes=db.total_changes
        await sync.sync('music')
        self.assertEqual(sync.queries.token,token)
        self.assertEqual(db.total_changes,changes)
        b.broadcast.assert_awaited_once()
        db.close()

    async def test_http_lists_never_call_source_refresh_and_stats_token_matches(self):
        from server import create_app
        with tempfile.TemporaryDirectory() as tmp:
            cfg=dict(catalog_sync_enabled=False,score_generation_enabled=False,video_roots=[],lan_networks=[],
                score_cache_dir=str(Path(tmp)/'scores'),cache_dir=str(Path(tmp)/'cache'),
                music_url='http://127.0.0.1:9/music',username='x',session_key='00'*32,
                password_hash='00'*32,password_salt='00'*16)
            app=create_app(cfg,Path(tmp)/'state.db')
            b=app['bridge']
            b.is_local=lambda req:True
            insert(b.db,1,title='本地快照',artist='合成歌手',media_kind='audio')
            b.db.commit()
            b.refresh=AsyncMock(side_effect=AssertionError('list must not await discovery'))
            async with TestClient(TestServer(app)) as client:
                for endpoint in ('/api/songs','/api/artists','/api/charts'):
                    response=await client.get(endpoint)
                    self.assertEqual(response.status,200)
                b.refresh.assert_not_awaited()
                b.refresh=AsyncMock()
                songs=await (await client.get('/api/songs')).json()
                stats=await (await client.get('/api/stats')).json()
                self.assertEqual(stats['catalogToken'],songs['catalogToken'])


if __name__=='__main__':unittest.main()
