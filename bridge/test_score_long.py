import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from score_long import sections, prepare_complete


class LongScoreTests(unittest.TestCase):
    def test_sections_cover_final_sample_without_gaps_or_duplicate_core(self):
        chunks=list(sections(7871987))
        self.assertEqual(chunks[0][0],0)
        self.assertEqual(chunks[-1][1],7871987)
        self.assertEqual(sum(b-a for a,b,_,_ in chunks),7871987)
        for before,after in zip(chunks,chunks[1:]):
            self.assertEqual(before[1],after[0])
        self.assertTrue(all(b-a<=300000 and d-c<=304000 for a,b,c,d in chunks))

    def test_long_song_merges_trimmed_notes_and_retains_ending(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);durations=[]
            def run(args,**kwargs):
                if str(args[-1]).endswith('input.wav'):
                    durations.append(float(args[args.index('-t')+1]))
                if str(args[-1]).endswith('.m4a'):Path(args[-1]).touch()
            def atomic(path,data):path.write_text(json.dumps(data))
            def fake_prepare(source,folder,allow_silent=False,lyric_offset_ms=0):
                self.assertTrue(allow_silent)
                n=math.ceil(durations[-1]*25)
                atomic(folder/'melody-v2.json',dict(notes=list(range(n)),confidence=[.9]*n,
                    stepMs=40,referenceVersion=3,method='fixture'))
            with patch('score_long.subprocess.check_output',return_value=b'915.003'),patch('score_long.subprocess.run',side_effect=run):
                prepare_complete(root/'source.wav',root,fake_prepare,atomic)
            result=json.loads((root/'melody-v2.json').read_text())
            self.assertEqual(len(result['notes']),math.ceil(915003/40))
            self.assertEqual(result['durationMs'],915003)
            self.assertEqual(result['notes'][7500],50)  # discard the 2 s leading overlap
            self.assertEqual(result['notes'][-1],425)  # retain the final 3 ms frame
            self.assertTrue(result['complete'])
            self.assertFalse((root/'long-work').exists())

    def test_failed_section_keeps_existing_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'melody-v2.json').write_text('previous')
            def fail(*args,**kwargs):raise RuntimeError('synthetic failure')
            with patch('score_long.subprocess.check_output',return_value=b'901'),patch('score_long.subprocess.run'):
                with self.assertRaises(RuntimeError):prepare_complete(root/'source',root,fail,lambda *args:None)
            self.assertEqual((root/'melody-v2.json').read_text(),'previous')
            self.assertFalse((root/'long-work').exists())
