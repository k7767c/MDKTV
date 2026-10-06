import unittest
from singing_segments import singing_intervals, gate_notes, lyric_start


class SingingTests(unittest.TestCase):
    def test_rejects_low_confidence_and_invalid_or_hallucinated_lengths(self):
        words=[dict(start=1,end=2,probability=.9),dict(start=5,end=6,probability=.1),
               dict(start=10,end=40,probability=.99),dict(start=float('nan'),end=9,probability=.9)]
        self.assertEqual(singing_intervals(words,50000),[[920,2080]])

    def test_clips_merges_and_does_not_fill_instrumental_gaps(self):
        words=[dict(start=0,end=1,probability=.9),dict(start=1,end=2,probability=.9),
               dict(start=4,end=8,probability=.9)]
        self.assertEqual(singing_intervals(words,5000),[[0,2080],[3920,5000]])

    def test_only_sung_frames_count_preserving_original_time_axis_and_nulls(self):
        notes=[60,None,62,63,64,65,66,67]
        gated,confidence=gate_notes(notes,[.9]*8,[[0,80],[200,280]])
        self.assertEqual(gated,[60,None,None,None,None,65,66,None])
        self.assertEqual(confidence,[.9,0,0,0,0,.9,.9,0])

    def test_instrumental_never_creates_targets(self):
        self.assertEqual(gate_notes([60]*100,[.9]*100,[]),([None]*100,[0]*100))

    def test_lyric_intro_guard_ignores_metadata_and_applies_offsets(self):
        self.assertEqual(lyric_start({'content':'[ti:Fixture]\n[00:00.00]\n[00:26.23]Fixture','offset':0}),25930)
        self.assertEqual(lyric_start({'content':'[offset:200]\n[00:01.00]Fixture','offset':100}),600)
