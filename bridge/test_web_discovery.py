import asyncio
import json
from pathlib import Path
import sqlite3
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer

import web_discovery
from web_discovery import CHARTS, WebDiscovery, parse_chart


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('CREATE TABLE songs(id INTEGER PRIMARY KEY,guid TEXT,data TEXT);'
                             'CREATE TABLE current_guids(guid TEXT PRIMARY KEY);')
        self.http = ClientSession()
        self.bridge = SimpleNamespace(cache=Path(self.temp.name) / 'cache', db=self.db,
            catalog=SimpleNamespace(queries=SimpleNamespace(token='catalog-a:1')), http=self.http)
        self.discovery = WebDiscovery(self.bridge)

    async def asyncTearDown(self):
        await self.http.close()
        self.db.close()
        self.temp.cleanup()

    def song(self, sid, title, artist, visible=True):
        self.db.execute('INSERT INTO songs VALUES(?,?,?)', (sid, 'fixture-' + str(sid),
            json.dumps({'title': title, 'artist': artist, 'media_kind': 'audio'})))
        if visible:
            self.db.execute('INSERT INTO current_guids VALUES(?)', ('fixture-' + str(sid),))
        self.db.commit()

    def chart_payload(self):
        return {'code': 200, 'playlist': {'id': CHARTS['hot'][1], 'tracks': [
            {'name': 'Actual title', 'artists': [{'name': 'Artist B'}, {'name': 'Artist A'}],
             'url': 'https://untrusted.example/third-party.mp3'}]}}

    def chart(self):
        return parse_chart(self.chart_payload(), 'hot')

    async def test_chart_matches_only_visible_owned_media_and_rebuilds_on_namespace_change(self):
        self.song(1, 'Actual title', 'Artist A / Artist B')
        self.song(2, 'Actual title', 'Different singer')
        self.song(3, 'Actual title', 'Artist A / Artist B', visible=False)
        chart = self.chart()
        self.discovery.save('chart-hot', chart)
        result = await self.discovery.chart('hot', cached_only=True)
        self.assertEqual([song['id'] for song in result['rows'][0]['matches']], [1])
        self.assertNotIn('untrusted.example', json.dumps(result))
        self.db.execute('DELETE FROM current_guids')
        self.db.execute("INSERT INTO current_guids VALUES('fixture-3')")
        self.db.commit()
        self.bridge.catalog.queries.token = 'catalog-b:1'
        result = await self.discovery.chart('hot', cached_only=True)
        self.assertEqual([song['id'] for song in result['rows'][0]['matches']], [3])
        self.assertEqual(result['catalogToken'], 'catalog-b:1')

    async def test_all_artists_and_invalid_parameters_do_not_request_remote_service(self):
        self.discovery.get = AsyncMock(side_effect=AssertionError('network should not run'))
        self.assertTrue((await self.discovery.artists(-1, -1))['all'])
        self.discovery.get.assert_not_awaited()
        for area, kind in ((999, 1), (7, 999)):
            with self.assertRaises(web.HTTPBadRequest):
                await self.discovery.artists(area, kind)
        with self.assertRaises(web.HTTPBadRequest):
            await self.discovery.chart('../../private')

    async def test_stale_chart_falls_back_with_fresh_owned_catalog_matching(self):
        self.song(1, 'Actual title', 'Artist A / Artist B')
        data = self.chart()
        data['fetched'] = time.time() - 7200
        self.discovery.save('chart-hot', data)
        self.discovery.get = AsyncMock(side_effect=asyncio.TimeoutError())
        result = await self.discovery.chart('hot')
        self.assertTrue(result['cached'])
        self.assertTrue(result['stale'])
        self.assertEqual(result['rows'][0]['matches'][0]['id'], 1)

    async def test_response_is_read_to_eof_across_network_chunks(self):
        async def chunked(request):
            response = web.StreamResponse(headers={'Content-Type': 'application/json'})
            await response.prepare(request)
            await response.write(b'{"code":')
            await asyncio.sleep(.01)
            await response.write(b'200,"artists":[]}')
            await response.write_eof()
            return response
        app = web.Application()
        app.router.add_get('/api/fixture', chunked)
        server = TestServer(app)
        await server.start_server()
        class LocalSession:
            def get(inner, url, **options):
                return self.http.get(server.make_url('/api/fixture'), **options)
        self.bridge.http = LocalSession()
        try:
            self.assertEqual(await self.discovery.get('/api/fixture', {}), {'code': 200, 'artists': []})
        finally:
            await server.close()

    async def test_artist_pagination_has_one_total_budget_and_returns_explicit_partial_data(self):
        calls = 0
        async def endless(path, params):
            nonlocal calls
            calls += 1
            await asyncio.sleep(.02)
            return {'code': 200, 'artists': [{'name': 'Artist ' + str(calls)}], 'more': True}
        self.discovery.get = endless
        with patch.object(web_discovery, 'DISCOVERY_BUDGET_SECONDS', .05, create=True):
            start = time.monotonic()
            result = await self.discovery.artists(7, 1)
        self.assertLess(time.monotonic() - start, .25)
        self.assertFalse(result['complete'])
        self.assertGreater(len(result['names']), 0)
        self.assertLess(calls, 30)
        self.assertIsNone(self.discovery.cached('artists-7-1'))

    async def test_chart_lock_wait_is_part_of_budget_without_cache(self):
        lock = self.discovery.locks['chart-hot'] = asyncio.Lock()
        await lock.acquire()
        self.discovery.get = AsyncMock(side_effect=AssertionError('network should not run'))
        try:
            started = time.monotonic()
            with patch.object(web_discovery, 'DISCOVERY_BUDGET_SECONDS', .03):
                with self.assertRaises(web.HTTPBadGateway):
                    await asyncio.wait_for(self.discovery.chart('hot'), .5)
            self.assertLess(time.monotonic() - started, .5)
            self.discovery.get.assert_not_awaited()
            self.assertTrue(lock.locked())
        finally:
            lock.release()
        self.assertFalse(lock.locked())

    async def test_artist_lock_wait_is_part_of_budget_without_cache(self):
        lock = self.discovery.locks['artists-7-1'] = asyncio.Lock()
        await lock.acquire()
        self.discovery.get = AsyncMock(side_effect=AssertionError('network should not run'))
        try:
            started = time.monotonic()
            with patch.object(web_discovery, 'DISCOVERY_BUDGET_SECONDS', .03):
                with self.assertRaises(web.HTTPBadGateway):
                    await asyncio.wait_for(self.discovery.artists(7, 1), .5)
            self.assertLess(time.monotonic() - started, .5)
            self.discovery.get.assert_not_awaited()
            self.assertTrue(lock.locked())
        finally:
            lock.release()
        self.assertFalse(lock.locked())

    async def test_chart_lock_timeout_rechecks_cache_created_while_waiting(self):
        self.song(1, 'Actual title', 'Artist A / Artist B')
        lock = self.discovery.locks['chart-hot'] = asyncio.Lock()
        await lock.acquire()
        self.discovery.get = AsyncMock(side_effect=AssertionError('network should not run'))
        try:
            with patch.object(web_discovery, 'DISCOVERY_BUDGET_SECONDS', .03):
                async with asyncio.TaskGroup() as tasks:
                    request = tasks.create_task(self.discovery.chart('hot'))
                    await asyncio.sleep(0)
                    self.discovery.save('chart-hot', self.chart())
                    result = await asyncio.wait_for(request, .5)
            self.assertTrue(result['cached'])
            self.assertTrue(result['stale'])
            self.assertEqual([song['id'] for song in result['rows'][0]['matches']], [1])
            self.discovery.get.assert_not_awaited()
            self.assertTrue(lock.locked())
        finally:
            lock.release()

    async def test_artist_lock_timeout_rechecks_cache_created_while_waiting(self):
        lock = self.discovery.locks['artists-7-1'] = asyncio.Lock()
        await lock.acquire()
        self.discovery.get = AsyncMock(side_effect=AssertionError('network should not run'))
        try:
            with patch.object(web_discovery, 'DISCOVERY_BUDGET_SECONDS', .03):
                async with asyncio.TaskGroup() as tasks:
                    request = tasks.create_task(self.discovery.artists(7, 1))
                    await asyncio.sleep(0)
                    self.discovery.save('artists-7-1', {
                        'names': ['validsinger'], 'complete': True, 'fetched': time.time(),
                        'source': 'fixture'})
                    result = await asyncio.wait_for(request, .5)
            self.assertTrue(result['cached'])
            self.assertTrue(result['stale'])
            self.assertEqual(result['names'], ['validsinger'])
            self.discovery.get.assert_not_awaited()
            self.assertTrue(lock.locked())
        finally:
            lock.release()

    async def test_concurrent_cold_chart_requests_share_successful_fetch(self):
        entered, finish = asyncio.Event(), asyncio.Event()
        async def fetch(path, params):
            entered.set()
            await finish.wait()
            return self.chart_payload()
        self.discovery.get = AsyncMock(side_effect=fetch)
        async with asyncio.timeout(1), asyncio.TaskGroup() as tasks:
            first = tasks.create_task(self.discovery.chart('hot'))
            await entered.wait()
            second = tasks.create_task(self.discovery.chart('hot'))
            await asyncio.sleep(0)
            finish.set()
        self.discovery.get.assert_awaited_once()
        self.assertFalse(first.result()['cached'])
        self.assertTrue(second.result()['cached'])
        self.assertEqual(first.result()['rows'], second.result()['rows'])
        self.assertFalse(self.discovery.locks['chart-hot'].locked())

    async def test_concurrent_cold_artist_requests_recheck_cache_inside_lock(self):
        entered, finish = asyncio.Event(), asyncio.Event()
        async def fetch(path, params):
            entered.set()
            await finish.wait()
            return {'code': 200, 'artists': [{'name': 'Valid Singer'}], 'more': False}
        self.discovery.get = AsyncMock(side_effect=fetch)
        async with asyncio.timeout(1), asyncio.TaskGroup() as tasks:
            first = tasks.create_task(self.discovery.artists(7, 1))
            await entered.wait()
            second = tasks.create_task(self.discovery.artists(7, 1))
            await asyncio.sleep(0)
            finish.set()
        self.discovery.get.assert_awaited_once()
        self.assertFalse(first.result()['cached'])
        self.assertTrue(second.result()['cached'])
        self.assertEqual(first.result()['names'], ['validsinger'])
        self.assertEqual(first.result()['names'], second.result()['names'])
        self.assertFalse(self.discovery.locks['artists-7-1'].locked())

    async def test_chart_error_rechecks_cache_written_during_failed_fetch(self):
        async def failing(path, params):
            self.discovery.save('chart-hot', self.chart())
            raise ValueError('invalid response')
        self.discovery.get = AsyncMock(side_effect=failing)
        result = await self.discovery.chart('hot')
        self.assertTrue(result['cached'])
        self.assertTrue(result['stale'])
        self.discovery.get.assert_awaited_once()

    async def test_artist_error_rechecks_cache_written_during_failed_fetch(self):
        async def failing(path, params):
            self.discovery.save('artists-7-1', {'names': ['validsinger'],
                'complete': True, 'fetched': time.time(), 'source': 'fixture'})
            raise ValueError('invalid response')
        self.discovery.get = AsyncMock(side_effect=failing)
        result = await self.discovery.artists(7, 1)
        self.assertTrue(result['cached'])
        self.assertTrue(result['stale'])
        self.assertEqual(result['names'], ['validsinger'])
        self.discovery.get.assert_awaited_once()

    def test_chart_rejects_nested_nonstring_and_blank_artist_names(self):
        for invalid in ({'name': 'Nested Singer'}, ['Nested Singer'], 7, True, None, '', '  '):
            with self.subTest(name=invalid):
                payload = self.chart_payload()
                payload['playlist']['tracks'][0]['artists'] = [{'name': invalid}]
                with self.assertRaises(ValueError):
                    parse_chart(payload, 'hot')

    async def test_artist_response_rejects_nested_and_nonstring_names(self):
        for invalid in ({'name': 'Nested Singer'}, ['Nested Singer'], 7, True, None):
            with self.subTest(name=invalid):
                self.discovery.get = AsyncMock(return_value={
                    'code': 200, 'artists': [{'name': invalid}], 'more': False})
                with self.assertRaises(web.HTTPBadGateway):
                    await self.discovery.artists(7, 1)
                self.assertIsNone(self.discovery.cached('artists-7-1'))

    async def test_artist_alias_validation_keeps_valid_partial_data_without_caching(self):
        self.discovery.get = AsyncMock(return_value={'code': 200,
            'artists': [{'name': 'Valid Singer', 'alias': [{'name': 'Nested Alias'}]}],
            'more': False})
        result = await self.discovery.artists(7, 1)
        self.assertEqual(result['names'], ['validsinger'])
        self.assertFalse(result['complete'])
        self.assertFalse(result['cached'])
        self.assertIsNone(self.discovery.cached('artists-7-1'))

    async def test_artist_names_are_normalized_deduplicated_and_never_empty(self):
        self.discovery.get = AsyncMock(return_value={'code': 200, 'artists': [
            {'name': '01.周杰伦', 'alias': ['', '  ', 'Ｊａｙ・Chou', 'Jay-Chou']},
            {'name': '周杰伦', 'alias': []}, {'name': '99._', 'alias': [' _ ']}],
            'more': False})
        result = await self.discovery.artists(7, 1)
        self.assertEqual(result['names'], ['周杰伦', 'jaychou'])
        self.assertTrue(result['complete'])
        self.assertEqual(self.discovery.cached('artists-7-1')['names'], result['names'])

    async def test_persisted_cache_rejects_nested_names_without_stringifying_them(self):
        self.discovery.root.mkdir(parents=True, exist_ok=True)
        for key, data in (
            ('chart-hot', dict(self.chart(), rows=[{'title': 'Actual title',
                'artists': [{'name': 'Nested Singer'}]}])),
            ('artists-7-1', {'names': [{'name': 'Nested Singer'}], 'fetched': time.time()}),
        ):
            with self.subTest(cache=key):
                (self.discovery.root / (key + '.json')).write_text(
                    json.dumps(data), encoding='utf-8')
                self.assertIsNone(self.discovery.cached(key))
        with self.assertRaises(web.HTTPNotFound):
            await self.discovery.chart('hot', cached_only=True)
        with self.assertRaises(web.HTTPNotFound):
            await self.discovery.artists(7, 1, cached_only=True)


if __name__ == '__main__':
    unittest.main()
