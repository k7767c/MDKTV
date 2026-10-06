import unittest
import numpy as np
from vocal_reference import frame_rms_dbfs, measured_notes


class VocalReferenceTests(unittest.TestCase):
    def test_real_silence_rejected_even_with_spurious_high_confidence(self):
        db = frame_rms_dbfs(np.zeros(16000))
        notes, _ = measured_notes([440]*len(db), [.99]*len(db), db)
        self.assertTrue(all(n is None for n in notes))

    def test_quiet_tonal_vocal_is_not_mistaken_for_silence(self):
        audio = .01*np.sin(2*np.pi*220*np.arange(16000)/16000)
        db = frame_rms_dbfs(audio)
        self.assertAlmostEqual(float(np.median(db)), -43.01, delta=.2)
        notes, _ = measured_notes([220]*len(db), [.85]*len(db), db)
        self.assertTrue(all(n == 57.0 for n in notes))

    def test_loud_but_unconfident_noise_is_still_rejected(self):
        audio = np.random.default_rng(4).normal(0,.1,16000)
        db = frame_rms_dbfs(audio)
        notes, _ = measured_notes([440]*len(db), [.1]*len(db), db)
        self.assertTrue(all(n is None for n in notes))

    def test_frame_centers_do_not_shift_an_onset(self):
        audio = np.r_[np.zeros(16000), np.ones(16000)*.1]
        db = frame_rms_dbfs(audio)
        self.assertLess(db[24], -100)
        self.assertGreater(db[25], -24)  # frame centered at exactly 1000 ms
        self.assertEqual(len(db), 51)

    def test_nonfinite_and_inaudible_frames_remain_empty(self):
        notes, _ = measured_notes([440, float('nan'), 440, 440, -1],
            [.9, .9, float('inf'), .9, .9],[-80,-20,-20,float('nan'),-20])
        self.assertTrue(all(n is None for n in notes))


if __name__ == '__main__':
    unittest.main()
