import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from catalog_sync import CatalogSync
from video_library import VideoLibrary


class CatalogTests(unittest.IsolatedAsyncioTestCase):
    async def test_source_failure_preserves_snapshot_and_other_source_and_ids(self):
        db = sqlite3.connect(':memory:'); db.row_factory = sqlite3.Row
        db.execute('CREATE TABLE songs(id INTEGER PRIMARY KEY,guid TEXT UNIQUE,data TEXT)')
        b = SimpleNamespace(db=db, cfg={}, broadcast=AsyncMock(),
            music_rows=AsyncMock(return_value={'m1': {'title': 'music'}}),
            videos=SimpleNamespace(scan=AsyncMock(return_value={'video:v1': {'title': 'MV'}})))
        sync = CatalogSync(b)
        await sync.sync('music'); await sync.sync('video')
        old_id = db.execute("SELECT id FROM songs WHERE guid='m1'").fetchone()[0]
        b.music_rows.side_effect = OSError('offline')
        b.videos.scan.return_value = {'video:v2': {'title': 'new MV'}}
        await sync.sync('music'); await sync.sync('video')
        self.assertEqual({r[0] for r in db.execute('SELECT guid FROM current_guids')}, {'m1','video:v2'})
        self.assertEqual(db.execute("SELECT id FROM songs WHERE guid='m1'").fetchone()[0], old_id)
        self.assertEqual(sync.status['music']['state'], 'offline')
        self.assertEqual(sync.status['video']['state'], 'ready')
        self.assertIsNotNone(db.execute("SELECT id FROM songs WHERE guid='video:v1'").fetchone())
        db.close()

    async def test_mpg_detection_add_delete_and_mount_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/'media'; root.mkdir()
            file = root/'song.MPG'; file.write_bytes(b'one')
            db = sqlite3.connect(':memory:')
            lib = VideoLibrary(db, [root], Path(tmp)/'cover')
            probe = json.dumps({'streams':[{'codec_type':'video','width':720,'height':576}], 'format':{'duration':'40'}}).encode()
            with patch('video_library.run_media', AsyncMock(return_value=probe)) as run:
                first = await lib.scan(); self.assertEqual(len(first), 1)
                self.assertEqual(first, await lib.scan()); self.assertEqual(run.await_count, 1)
                (root/'added.mpeg').write_bytes(b'two')
                self.assertEqual(len(await lib.scan()), 2)
                file.unlink(); self.assertEqual(len(await lib.scan()), 1)
                (root/'added.mpeg').unlink(); root.rmdir()
                with self.assertRaises(OSError): await lib.scan()
            db.close()

    async def test_failed_probe_preserves_existing_record_and_retries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); file = root/'song.mpg'; file.write_bytes(b'one')
            db = sqlite3.connect(':memory:'); lib = VideoLibrary(db, [root], root/'covers')
            probe = json.dumps({'streams':[{'codec_type':'video','width':720,'height':576}]}).encode()
            with patch('video_library.run_media', AsyncMock(return_value=probe)):
                old = await lib.scan()
            file.write_bytes(b'copying more bytes')
            with patch('video_library.run_media', AsyncMock(side_effect=ValueError('partial'))):
                self.assertEqual(old, await lib.scan())
            with patch('video_library.run_media', AsyncMock(return_value=probe)) as run:
                self.assertNotEqual(old, await lib.scan()); run.assert_awaited_once()
            db.close()
