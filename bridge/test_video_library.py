import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
from video_library import VideoLibrary, has_motion_video


class VideoTests(unittest.IsolatedAsyncioTestCase):
    def test_album_art_not_mv(self):
        self.assertFalse(has_motion_video({'streams':[dict(codec_type='video',width=500,height=500,disposition={'attached_pic':1})]}))
        self.assertTrue(has_motion_video({'streams':[dict(codec_type='video',width=1920,height=1080,disposition={})]}))

    async def test_scan_verifies_stream_caches_metadata_and_hides_paths(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root)/'media';directory.mkdir()
            file=directory/'Fixture.mp4';file.write_bytes(b'fixture')
            db=sqlite3.connect(':memory:')
            library=VideoLibrary(db,[directory],Path(root)/'covers')
            result={'streams':[dict(codec_type='video',width=1280,height=720)],'format':{'duration':'42'}}
            with patch('video_library.run_media',AsyncMock(return_value=json.dumps(result).encode())) as run:
                first=await library.scan();second=await library.scan()
                self.assertEqual(first,second);run.assert_awaited_once()
            guid=next(iter(first));self.assertEqual(first[guid]['media_kind'],'mv')
            self.assertNotIn(str(directory),json.dumps(first))
            self.assertEqual(library.path(guid),file.resolve())
            self.assertFalse(library.allowed(Path(root)/'other.mp4'))
            file.unlink()
            self.assertEqual(await library.scan(),{});self.assertIsNone(library.path(guid))
            db.close()
