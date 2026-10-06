import asyncio
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock
from online_tracks import OnlineTracks


class MatchTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory()
        root=Path(self.temp.name)
        self.song={'title':'Song','artist':'Singer','duration':227.5}
        self.bridge=SimpleNamespace(cfg={'online_tracks_enabled':True},song=lambda sid:self.song,
                                    scores=SimpleNamespace(folder=lambda sid:root/str(sid)))
        self.online=OnlineTracks(self.bridge)

    async def asyncTearDown(self):
        await self.online.close();self.temp.cleanup()

    async def test_mv_keeps_embedded_audio_without_online_search_or_job(self):
        self.song['media_kind']='mv'
        self.online.call=AsyncMock()
        self.assertEqual(self.online.request(1)['reason'],'embedded_mv_audio')
        self.assertEqual(self.online.jobs,{})
        await self.online.match(1)
        self.online.call.assert_not_awaited()

    def test_rejects_wrong_artist_live_version_and_duration(self):
        self.assertTrue(self.online.matches(self.song,dict(self.song,title='<em>Song</em>',duration=227)))
        for changes in [{'title':'Song (Live)'},{'artist':'Cover Singer'},{'duration':230},{'duration':0},{'duration':'bad'}]:
            self.assertFalse(self.online.matches(self.song,dict(self.song,**changes)))

    def test_media_hosts_and_ports_are_closed(self):
        self.assertEqual(self.online.media_url('http://fsandroid.kugou.com/test'),'https://fsandroid.kugou.com/test')
        for url in ['http://127.0.0.1/test','https://fsandroid.kugou.com.evil.example/test',
                    'https://user:pass@fsandroid.kugou.com/test','https://fsandroid.kugou.com:8084/test',
                    'file:///etc/passwd']:
            with self.assertRaises(ValueError):self.online.media_url(url)

    async def test_login_miss_is_deduplicated_and_cached(self):
        self.online.call=AsyncMock(return_value={'status':'login_required'})
        self.online.request(1);self.online.request(1)
        await self.online.jobs[1]
        state=self.online.request(1)
        self.assertEqual(state['reason'],'login_required')
        self.online.call.assert_awaited_once()
        self.assertEqual(list(self.online.jobs),[1])

    async def test_search_mismatch_never_downloads(self):
        self.online.call=AsyncMock(return_value={'status':'ok','candidates':[dict(self.song,duration=300)]})
        self.online.download=AsyncMock()
        self.online.request(2);await self.online.jobs[2]
        self.assertEqual(self.online.state(2)['reason'],'no_exact_match')
        self.online.call.assert_awaited_once();self.online.download.assert_not_awaited()

    async def test_selected_song_only_and_cached_success_survives_restart(self):
        candidate=dict(self.song,hash='a'*32)
        self.online.call=AsyncMock(side_effect=[{'status':'ok','candidates':[candidate]},{'status':'resource','kind':'split'}])
        async def download(sid,song,resource):
            folder=self.bridge.scores.folder(sid);folder.mkdir(parents=True,exist_ok=True)
            (folder/'accompaniment.m4a').write_bytes(b'fixture')
        self.online.download=AsyncMock(side_effect=download)
        self.online.request(2);await self.online.jobs[2]
        self.assertFalse(self.bridge.scores.folder(1).exists())
        reopened=OnlineTracks(self.bridge);reopened.call=AsyncMock()
        self.assertEqual(reopened.request(2)['reason'],'cached_accompaniment')
        reopened.call.assert_not_awaited();self.assertEqual(reopened.jobs,{})

    async def test_provider_failure_has_cooldown_and_never_raises_to_player(self):
        self.online.call=AsyncMock(side_effect=OSError('test'))
        self.online.request(3);await self.online.jobs[3]
        self.assertEqual(self.online.request(3)['reason'],'provider_error')
        self.online.call.assert_awaited_once()

    async def test_disabled_provider_never_makes_requests(self):
        self.bridge.cfg['online_tracks_enabled']=False
        self.online.call=AsyncMock()
        self.assertEqual(self.online.request(1)['status'],'unavailable')
        self.assertEqual(self.online.jobs,{})
