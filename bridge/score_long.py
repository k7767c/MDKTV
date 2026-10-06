"""Bounded-memory processing of complete long recordings, including their ending."""
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time


def sections(duration_ms, core_ms=300000, overlap_ms=2000):
    for start in range(0, duration_ms, core_ms):
        end=min(duration_ms,start+core_ms)
        yield start,end,max(0,start-overlap_ms),min(duration_ms,end+overlap_ms)


def prepare_complete(source, folder, prepare, atomic_json):
    duration=float(subprocess.check_output(['/usr/bin/ffprobe','-v','error','-show_entries',
        'format=duration','-of','default=nw=1:nk=1',str(source)]).strip())
    if not math.isfinite(duration) or duration<=0:raise ValueError('invalid duration')
    try:core_ms=max(60,min(300,int(os.environ.get('SCORE_SEGMENT_SECONDS','300'))))*1000
    except ValueError:core_ms=300000
    if duration*1000<=core_ms:
        return prepare(source,folder)
    duration_ms=round(duration*1000)
    scratch=folder/'long-work';scratch.mkdir(exist_ok=True)
    pcm=scratch/'accompaniment.f32';notes=[];confidence=[];result=None;intervals=[];words=0;raw_voiced=0
    try:
        with pcm.open('wb') as out:
            for start,end,read_start,read_end in sections(duration_ms,core_ms=core_ms):
                part=scratch/'part';part.mkdir(exist_ok=True)
                wav=part/'input.wav'
                subprocess.run(['/usr/bin/ffmpeg','-v','error','-y','-threads','1','-ss',str(read_start/1000),
                    '-i',str(source),'-t',str((read_end-read_start)/1000),'-ar','44100','-ac','2',str(wav)],check=True)
                prepare(wav,part,allow_silent=True,lyric_offset_ms=read_start)
                result=json.loads((part/'melody-v2.json').read_text())
                step=result['stepMs'];a=(start-read_start)//step
                size=math.ceil((end-start)/step)
                segment=result['notes'][a:a+size]
                if len(segment)!=size:raise ValueError('incomplete long melody section')
                notes.extend(segment);confidence.extend(result['confidence'][a:a+size])
                for x,y in result.get('singing',{}).get('intervals',[]):
                    x,y=max(start,x+read_start),min(end,y+read_start)
                    if y>x:intervals.append([x,y])
                words+=result.get('singing',{}).get('wordCount',0)
                raw_voiced+=result.get('rawVoicedFrames',0)
                subprocess.run(['/usr/bin/ffmpeg','-v','error','-threads','1','-i',str(part/'accompaniment.m4a'),
                    '-ss',str((start-read_start)/1000),'-t',str((end-start)/1000),
                    '-ar','44100','-ac','2','-f','f32le','pipe:1'],stdout=out,check=True)
                atomic_json(folder/'status.json',{'status':'preparing','message':'分段生成完整长曲音轨',
                    'progress':min(95,round(end/duration_ms*95)),'processedMs':end,'durationMs':duration_ms})
                # Fixed private scratch path; never follows an external/user-selected directory.
                if part.resolve().parent!=scratch.resolve():raise ValueError('invalid scratch path')
                shutil.rmtree(part)
        if not notes:raise ValueError('missing melody frames')
        target=folder/'accompaniment.tmp.m4a'
        subprocess.run(['/usr/bin/ffmpeg','-v','error','-y','-threads','1','-f','f32le','-ar','44100','-ac','2',
            '-i',str(pcm),'-c:a','aac','-b:a','256k','-movflags','+faststart',str(target)],check=True)
        target.replace(folder/'accompaniment.m4a')
        result.update(durationMs=duration_ms,notes=notes,confidence=confidence,revision=str(time.time_ns()),
            complete=True,method=result['method']+'+complete-sections')
        if 'singing' in result:
            result['singing'].update(intervals=intervals,wordCount=words)
            result['rawVoicedFrames']=raw_voiced
        previous=folder/'melody-v2.json'
        if previous.is_file():
            try:
                old=json.loads(previous.read_text())
                if not isinstance(old,dict):old={}
            except (ValueError,OSError):old={}
            old_version=old.get('referenceVersion',0)
            if type(old_version) is not int:old_version=0
            if old_version<result.get('referenceVersion',0):
                backup=folder/('melody-reference-v'+str(old_version)+'.json')
                if not backup.exists():backup.write_bytes(previous.read_bytes())
        atomic_json(folder/'melody-v2.json',result)
        atomic_json(folder/'status.json',{'status':'ready','message':'完整旋律准备完成','progress':100})
    finally:
        if scratch.resolve().parent==folder.resolve():shutil.rmtree(scratch)
