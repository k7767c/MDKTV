"""Time-domain silence gate for separated singing, in actual dBFS."""
import numpy as np

REFERENCE_VERSION = 4
MIN_PERIODICITY = .45
MIN_VOCAL_DBFS = -55.0


def frame_rms_dbfs(audio, hop=640, window=1024):
    """Frames are centered at 0, hop, ... like CREPE with pad=True."""
    audio = np.asarray(audio, dtype=np.float64).reshape(-1)
    if not audio.size:
        return np.empty(0)
    padded = np.pad(audio, (window//2, window//2))
    energy = np.concatenate(([0.0], np.cumsum(padded*padded)))
    starts = np.arange(audio.size//hop+1)*hop
    power = np.maximum((energy[starts+window]-energy[starts])/window, 1e-20)
    return 10*np.log10(power)


def measured_notes(pitch, periodicity, rms_dbfs):
    """Retain confident measured pitches; silence and invalid values stay unvoiced."""
    notes, confidence = [], []
    for p, c, db in zip(pitch, periodicity, rms_dbfs):
        valid = (np.isfinite(p) and np.isfinite(c) and np.isfinite(db)
                 and p > 0 and c >= MIN_PERIODICITY and db >= MIN_VOCAL_DBFS)
        notes.append(round(float(69+12*np.log2(p/440)), 3) if valid else None)
        confidence.append(round(float(c), 3) if valid else 0)
    return notes, confidence
