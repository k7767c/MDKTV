import unittest
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch, AsyncMock
from aiohttp.test_utils import TestClient, TestServer
from aiohttp import web
from video_playback import VideoPlayback


class VideoPlaybackTests(unittest.IsolatedAsyncioTestCase):
    def test_audio_tracks_are_independent_renditions(self):
        value=VideoPlayback.renditions(2)
        self.assertIn('a:0,agroup:audio,default:yes,name:audio0',value)
        self.assertIn('a:1,agroup:audio,default:no,name:audio1',value)
        self.assertIn('v:0,agroup:audio,name:video',value)
        self.assertEqual(VideoPlayback.renditions(0),'v:0,name:video')

    async def test_master_and_each_audio_playlist_and_segment_are_servable(self):
        # Exercise the real route registration, not a second test-only regex.
        from server import create_app
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            app=create_app(dict(catalog_sync_enabled=False,video_roots=[],score_generation_enabled=False,
                music_url='http://127.0.0.1:9/music',music_token='fixture',session_key='fixture',lan_networks=[],
                username='fixture',password_salt='00'*16,password_hash='00'*32),root/'state.db')
            b=app['bridge'];b.is_local=lambda req:True
            folder=root/'fixture';folder.mkdir()
            files={'master.m3u8':'#EXTM3U\nindex_video.m3u8\n','index_video.m3u8':'#EXTM3U\nsegment_video_00000.ts\n',
                   'index_audio0.m3u8':'#EXTM3U\nsegment_audio0_00000.ts\n','index_audio1.m3u8':'#EXTM3U\nsegment_audio1_00000.ts\n',
                   'segment_video_00000.ts':'video','segment_audio0_00000.ts':'audio0','segment_audio1_00000.ts':'audio1','complete':''}
            for name,value in files.items():(folder/name).write_text(value, newline='\n')
            b.video_playback.folder=lambda sid:(root/'source.mpg',folder)
            async with TestClient(TestServer(app)) as client:
                for name,value in files.items():
                    if name=='complete':continue
                    response=await client.get('/mv/1/'+name)
                    self.assertEqual(response.status,200,name);self.assertEqual(await response.text(),value)
                self.assertEqual((await client.get('/mv/1/complete')).status,404)
                self.assertEqual((await client.get('/mv/1/index_audio2.m3u8')).status,404)
