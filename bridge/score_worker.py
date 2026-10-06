"""Offline, local-only vocal melody preparation. Run in the separate pitch venv."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

# Set these before importing numpy/torch so their native pools honor the budget.
try:
    WORKER_THREADS = max(1, min(2, int(os.environ.get('SCORE_WORKER_THREADS', '1'))))
except ValueError:
    WORKER_THREADS = 1
for _name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'NUMEXPR_NUM_THREADS'):
    os.environ[_name] = str(WORKER_THREADS)

from vocal_reference import REFERENCE_VERSION, frame_rms_dbfs, measured_notes
from score_long import prepare_complete
from singing_segments import gate_notes


def atomic_json(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False), encoding='utf-8')
    tmp.replace(path)


def prepare(source, folder, allow_silent=False, lyric_offset_ms=0):
    import numpy as np
    import torch
    import torchaudio
    import torchcrepe
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    torch.set_num_threads(WORKER_THREADS)
    if torch.get_num_interop_threads()!=1:torch.set_num_interop_threads(1)
    def stage(message, progress):
        atomic_json(folder/'status.json', {'status':'preparing', 'message':message, 'progress':progress})
    stage('读取歌曲音源', 5)
    raw = subprocess.check_output(['/usr/bin/ffmpeg','-v','error','-threads','1','-i',str(source),
        '-t','900','-vn','-ac','2','-ar','44100','-f','f32le','pipe:1'])
    mix = torch.from_numpy(np.frombuffer(raw,dtype='<f4').copy().reshape(-1,2).T)
    del raw
    if mix.shape[-1] < 44100:
        raise ValueError('audio too short')
    stage('分离原唱人声，首次处理需要几分钟', 15)
    # One model load per worker; bounded sections reuse it instead of repeatedly
    # reloading the same weights for each segment of a long recording.
    model = getattr(prepare, '_model', None)
    if model is None:
        model = prepare._model = get_model('htdemucs').cpu().eval()
    ref = mix.mean(0)
    mean, std = ref.mean(), ref.std().clamp_min(1e-6)
    with torch.inference_mode():
        stems = apply_model(model, ((mix-mean)/std)[None], device='cpu', shifts=0,
            split=True, overlap=.10, progress=False, num_workers=0)[0] * std + mean
        vocals = stems[model.sources.index('vocals')]
        accompaniment = stems.sum(0)-vocals
        # Save a time-aligned accompaniment for optional original/accompaniment switching.
        accompaniment_tmp=folder/'accompaniment.tmp.m4a'
        proc = subprocess.Popen(['/usr/bin/ffmpeg','-v','error','-y','-threads','1','-f','f32le','-ar','44100','-ac','2',
            '-i','pipe:0','-c:a','aac','-b:a','256k','-movflags','+faststart',str(accompaniment_tmp)],stdin=subprocess.PIPE)
        proc.communicate(accompaniment.T.contiguous().numpy().astype('<f4').tobytes())
        if proc.returncode:
            raise RuntimeError('accompaniment encode failed')
        accompaniment_tmp.replace(folder/'accompaniment.m4a')
        mono = torchaudio.functional.resample(vocals.mean(0,keepdim=True),44100,16000)
    del stems, vocals, accompaniment, mix, model
    duration = round(mono.shape[-1]*1000/16000)
    stage('识别实际演唱区间，排除前奏和间奏', 50)
    singing_wav = folder/'singing-work.wav'
    timing_file = folder/'singing-work.json'
    root = Path(__file__).resolve().parent
    try:
        # Use the existing separated vocals, so accompaniment doesn't drive word timing.
        proc = subprocess.run(['/usr/bin/ffmpeg','-v','error','-y','-threads','1','-f','f32le','-ar','16000','-ac','1',
                               '-i','pipe:0',str(singing_wav)],
                              input=mono[0].numpy().astype('<f4').tobytes(), check=True)
        lyric_file=Path(os.environ.get('SCORE_LYRICS',str(folder/'lyrics.json')))
        extra=['--lyrics',str(lyric_file),'--offset-ms',str(lyric_offset_ms)] if lyric_file.is_file() else []
        command=[os.environ.get('ALIGNMENT_PYTHON',str(root/'alignment-venv/bin/python')),str(root/'singing_segments.py'),
                 str(singing_wav),str(timing_file),os.environ.get('ALIGNMENT_MODEL',str(root/'alignment-models-small'))]
        # The scheduler bounds active worker time and terminates the entire group.
        # A wall-clock subprocess timeout would falsely expire while fnOS pauses
        # the group to give playback priority.
        subprocess.run(command+extra,check=True)
        singing = json.loads(timing_file.read_text())
    finally:
        singing_wav.unlink(missing_ok=True)
        timing_file.unlink(missing_ok=True)
    notes, confidence = [], []
    hop, chunk = 640, 320000  # 40 ms grid; 20 s bounded inference windows.
    with torch.inference_mode():
        for start in range(0,mono.shape[-1],chunk):
            sample = mono[:,start:start+chunk]
            frames = (sample.shape[-1]+hop-1)//hop
            # A file may end a few samples after a 20 s boundary; CREPE/STFT needs a full window.
            if sample.shape[-1] < 1024:
                sample = torch.nn.functional.pad(sample,(0,1024-sample.shape[-1]))
            pitch, periodicity = torchcrepe.predict(sample,16000,hop,65,1000,'full',
                batch_size=32,device='cpu',return_periodicity=True)
            if periodicity.shape[-1] > 1:
                periodicity = torchcrepe.filter.median(periodicity,3)
            # A-weighted average spectral bins are not dBFS: applying -45 there
            # discarded confident, audible isolated vocals. Gate actual waveform RMS.
            rms = frame_rms_dbfs(sample[0].numpy(), hop)
            chunk_notes, chunk_confidence = measured_notes(
                pitch[0,:frames].tolist(),periodicity[0,:frames].tolist(),rms[:frames])
            notes.extend(chunk_notes); confidence.extend(chunk_confidence)
            stage('提取原唱旋律', 55+int(40*min(start+chunk,mono.shape[-1])/mono.shape[-1]))
    if not allow_silent and sum(n is not None for n in notes) < 50:
        raise ValueError('insufficient vocal melody')
    raw_voiced = sum(n is not None for n in notes)
    notes, confidence = gate_notes(notes, confidence, singing['intervals'])
    result = {'status':'ready','method':'demucs-htdemucs+crepe-full+whisper-words-v4',
        'referenceVersion':REFERENCE_VERSION,'revision':str(time.time_ns()),'stepMs':40,'frameCenterMs':0,
        'durationMs':duration,'notes':notes,'confidence':confidence,'experimental':True,'complete':True,
        'reference':'自动识别演唱区间与原唱音高','accompaniment':True,
        'singing':singing, 'rawVoicedFrames':raw_voiced}
    previous=folder/'melody-v2.json'
    if previous.is_file():
        try:
            old=json.loads(previous.read_text())
            if not isinstance(old,dict):old={}
        except (ValueError,OSError):old={}
        old_version=old.get('referenceVersion',0)
        if type(old_version) is not int:old_version=0
        if old_version<REFERENCE_VERSION:
            backup=folder/('melody-reference-v'+str(old_version)+'.json')
            if not backup.exists():backup.write_bytes(previous.read_bytes())
    atomic_json(folder/'melody-v2.json',result)
    stage('旋律准备完成',100)


if __name__ == '__main__':
    if hasattr(os,'nice'):
        os.nice(10)
    target = Path(sys.argv[2]); target.mkdir(parents=True,exist_ok=True)
    try:
        prepare_complete(Path(sys.argv[1]),target,prepare,atomic_json)
    except Exception as exc:
        atomic_json(target/'status.json',{'status':'error','message':'原唱旋律处理失败，请稍后重试',
                                         'errorType':type(exc).__name__})
        raise
