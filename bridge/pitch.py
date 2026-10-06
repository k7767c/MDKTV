"""Conservative periodic pitch from the mixed recording, for entertainment only.
This is not vocal separation or a professionally annotated melody score.
"""
import numpy as np


def reference_pitch(pcm, rate=8000, step=800):
    samples = np.frombuffer(pcm, dtype='<f4')
    result = []
    length = 1024
    for offset in range(0, max(0, len(samples)-length+1), step):
        x = samples[offset:offset+length].astype(np.float64)
        x -= x.mean()
        if np.sqrt(np.mean(x*x)) < 0.008:
            result.append(None); continue
        # Normalized autocorrelation; only stable periodic frames are retained.
        transformed = np.fft.rfft(x, 2048)
        corr = np.fft.irfft(transformed * transformed.conjugate(), 2048)[:length]
        energies = np.r_[0., np.cumsum(x*x)]
        lo, hi = rate//800, rate//70
        lags = np.arange(lo, hi+1)
        denom = np.sqrt(np.maximum(1e-12, energies[length-lags] * (energies[length]-energies[lags])))
        values = corr[lags]/denom
        peaks = [i for i in range(1,len(values)-1) if values[i] >= values[i-1] and values[i] > values[i+1]]
        if not peaks:
            result.append(None); continue
        best = max(values[i] for i in peaks)
        candidates = [i for i in peaks if values[i] >= max(.82, best*.95)]
        if not candidates:
            result.append(None); continue
        lag = float(lags[candidates[0]])
        midi = 69 + 12*np.log2((rate/lag)/440)
        result.append(round(float(midi), 3))
    return {'stepMs': round(step*1000/rate), 'frameCenterMs':64, 'notes':result,
            'method':'mixed-recording-periodic-pitch', 'experimental':True,
            'durationMs':round(len(samples)*1000/rate)}
