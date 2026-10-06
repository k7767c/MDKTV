"""Offline faster-whisper word timing on separated vocals; no network audio upload.

Like UltraSinger's transcription-based singing mask, word intervals constrain
the independently measured pitch. Recognition is experimental, not a hand-made chart.
"""
import argparse
import json
import math
import re
from pathlib import Path


def singing_intervals(words, duration_ms, padding_ms=80):
    intervals = []
    for word in words:
        start, end = word['start'], word['end']
        probability = word.get('probability', 0)
        if not all(math.isfinite(v) for v in (start, end, probability)):
            continue
        if probability < .5 or end <= start or end-start > 12:
            continue
        a, b = max(0, round(start*1000)-padding_ms), min(duration_ms, round(end*1000)+padding_ms)
        if b > a:
            intervals.append([a, b])
    merged = []
    for a, b in sorted(intervals):
        if merged and a <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    return merged


def gate_notes(notes, confidence, intervals, step_ms=40):
    """Keep real measured notes within singing bounds; never invent or stretch notes."""
    result, weights = [], []
    j = 0
    for i, (note, weight) in enumerate(zip(notes, confidence)):
        at = i*step_ms
        while j < len(intervals) and intervals[j][1] <= at:
            j += 1
        singing = j < len(intervals) and intervals[j][0] <= at < intervals[j][1]
        result.append(note if singing else None)
        weights.append(weight if singing and note is not None else 0)
    return result, weights


def lyric_start(lyrics):
    """Use the supplied timed lyrics to exclude a hallucinated/speech-only intro."""
    starts=[]
    content=lyrics.get('content','')
    adjustment=int(lyrics.get('offset') or 0)
    # Match Android Lyrics.parse: positive LRC offset advances, API adjustment delays.
    tag=re.search(r'\[offset:([+-]?\d+)\]',content,re.I)
    offset=int(tag[1]) if tag else 0
    for line in content.splitlines():
        times=re.findall(r'\[(\d+):(\d+(?:\.\d+)?)\]',line)
        text=re.sub(r'\[[^]]+\]','',line).strip()
        if times and text and text not in ('前奏','间奏','尾奏','纯音乐','Intro','Instrumental'):
            starts.extend(round((int(m)*60+float(s))*1000)-offset+adjustment for m,s in times if float(s)<60)
    return max(0,min(starts)-300) if starts else 0


def transcribe(source, model_dir, lyrics=None, offset_ms=0):
    import os
    from faster_whisper import WhisperModel
    if not (Path(model_dir)/'model.bin').is_file():
        raise ValueError('Local singing timing model not installed')
    try:threads=max(1,min(2,int(os.environ.get('SCORE_WORKER_THREADS','1'))))
    except ValueError:threads=1
    model = WhisperModel(str(model_dir), device='cpu', compute_type='int8', cpu_threads=threads,
                         local_files_only=True)
    # Speech VAD/no-speech scores reject sustained singing. The separated vocal
    # waveform + measured pitch provide the acoustic gate; keep ASR word timing.
    segments, info = model.transcribe(str(source), word_timestamps=True, vad_filter=False,
        condition_on_previous_text=False, beam_size=5, temperature=0,
        hallucination_silence_threshold=None)
    words = []
    for segment in segments:
        words.extend({'start': w.start, 'end': w.end, 'probability': w.probability}
                     for w in segment.words or [] if w.word.strip())
    intervals=singing_intervals(words, round(info.duration*1000))
    first=max(0,lyric_start(lyrics or {})-offset_ms)
    intervals=[[max(a,first),b] for a,b in intervals if b>first]
    return {'method':'faster-whisper-small-1.2.1', 'language':info.language,
            'intervals':intervals, 'lyricIntroGuardMs':first,
            'wordCount':len(words), 'experimental':True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source'); parser.add_argument('output'); parser.add_argument('model_dir')
    parser.add_argument('--lyrics');parser.add_argument('--offset-ms',type=int,default=0)
    args = parser.parse_args()
    lyrics=json.loads(Path(args.lyrics).read_text(encoding='utf-8-sig')) if args.lyrics else {}
    result = transcribe(args.source, args.model_dir,lyrics,args.offset_ms)
    Path(args.output).write_text(json.dumps(result), encoding='utf-8')
