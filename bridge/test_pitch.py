import unittest
import numpy as np
from pitch import reference_pitch


class ReferenceTests(unittest.TestCase):
    def test_silence_has_no_reference(self):
        r=reference_pitch(np.zeros(16000,dtype='<f4').tobytes())
        self.assertTrue(all(n is None for n in r['notes']))

    def test_known_tone_reference_is_measured(self):
        pcm=(.4*np.sin(2*np.pi*440*np.arange(16000)/8000)).astype('<f4')
        r=reference_pitch(pcm.tobytes())
        valid=[v for v in r['notes'] if v is not None]
        self.assertGreater(len(valid),10)
        self.assertLess(abs(np.median(valid)-69),.4)

    def test_random_noise_not_given_confident_pitch(self):
        rng=np.random.default_rng(17)
        r=reference_pitch(rng.normal(0,.1,16000).astype('<f4').tobytes())
        self.assertLess(sum(v is not None for v in r['notes']),2)

if __name__=='__main__':unittest.main()
