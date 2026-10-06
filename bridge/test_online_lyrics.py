import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock
from online_lyrics import OnlineLyrics, recording_match, timed_lrc


class LyricMatchTests(unittest.TestCase):
    def test_live_suffix_requires_album_and_duration_evidence(self):
        song={'title':'Song','artist':'Singer','album':'Singer I AM 世界巡回演唱会小巨蛋（终极典藏版）','duration':528.765}
        candidate={'title':'Song (Live)','artist':'Singer','album':'I AM 世界巡回演唱会','duration':528}
        self.assertTrue(recording_match(song,candidate))
        for changes in [{'duration':242},{'album':'Another Tour'},{'artist':'Cover'},{'title':'Song (DJ版)'}]:
            self.assertFalse(recording_match(song,dict(candidate,**changes)))
        self.assertFalse(recording_match(dict(song,album=''),candidate))

    def test_does_not_discard_language_or_medley_labels(self):
        song={'title':'Song','artist':'Singer','album':'I AM 世界巡回演唱会','duration':300}
        for title in ['Song (粤语版)','Song / Other Song','Song (Solo)']:
            self.assertFalse(recording_match(song,dict(song,title=title)))

    def test_word_times_are_relative_to_recording_not_the_first_vocal(self):
        text='[349100,1000]<0,400,0>甲<400,600,0>乙'
        self.assertEqual(timed_lrc(text,529),'[05:49.100]<05:49.100>甲<05:49.500>乙<05:50.100>')
        # A long intro must not silently turn into vocals at zero.
        self.assertNotIn('[00:',timed_lrc(text,529))

    def test_rejects_empty_or_out_of_recording_lyrics(self):
        for text in ['plain','[999999,1000]<0,1000,0>text']:
            with self.assertRaises(ValueError):timed_lrc(text,200)


class LyricCacheTests(unittest.IsolatedAsyncioTestCase):
    async def test_live_overrides_wrong_nas_lyrics_and_caches_per_recording(self):
        with tempfile.TemporaryDirectory() as tmp:
            song={'title':'Song','artist':'Singer','album':'I AM 世界巡回演唱会','duration':300}
            provider=SimpleNamespace(call=AsyncMock(side_effect=[
                {'candidates':[dict(song,title='Song (Live)',hash='a'*32)]},
                {'status':'ok','content':'[90000,1000]<0,1000,0>text','providerId':'fixture'}]))
            b=SimpleNamespace(cfg={'online_tracks_enabled':True},song=lambda sid:song,
                              scores=SimpleNamespace(folder=lambda sid:Path(tmp)/str(sid)),online_tracks=provider)
            service=OnlineLyrics(b)
            old={'content':'[00:10.0]wrong version','source':'NAS'}
            first=await service.get(1,old);second=await service.get(1,old)
            self.assertEqual(first,second);self.assertIn('[01:30.000]',first['content'])
            self.assertEqual(provider.call.await_count,2)
            self.assertTrue(service.path(1).is_file())
            await service.close()

    async def test_failed_lookup_preserves_original_and_does_not_loop(self):
        with tempfile.TemporaryDirectory() as tmp:
            b=SimpleNamespace(cfg={'online_tracks_enabled':True},song=lambda sid:{'title':'Song Live','duration':300},
                              scores=SimpleNamespace(folder=lambda sid:Path(tmp)),
                              online_tracks=SimpleNamespace(call=AsyncMock(return_value={'status':'error'})))
            service=OnlineLyrics(b);old={'content':'existing'}
            self.assertEqual(await service.get(1,old),old);self.assertEqual(await service.get(1,old),old)
            b.online_tracks.call.assert_awaited_once();await service.close()
