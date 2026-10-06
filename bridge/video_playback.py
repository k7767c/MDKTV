"""Bounded, on-demand video compatibility; never uses the audio-only HLS path."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import time
from aiohttp import web
from video_library import run_media, has_motion_video


class VideoPlayback:
    def __init__(self, bridge):
        self.b = bridge
        self.root = Path(bridge.cfg.get('video_cache_dir', str(bridge.cache.parent/'mv-cache')))
        self.tasks = {}
        self.slot = asyncio.Semaphore(1)
        self.limit = int(bridge.cfg.get('video_cache_bytes', 2*1024**3))

    def folder(self, sid):
        row = self.b.db.execute('SELECT guid FROM songs WHERE id=?',(sid,)).fetchone()
        if not row or not row[0].startswith('video:'):
            raise web.HTTPNotFound(text='本地视频不存在')
        path = self.b.videos.path(row[0])
        if not path:
            raise web.HTTPNotFound(text='视频已移动或不可读取')
        stat = path.stat()
        key = hashlib.sha256(f'{row[0]}:{stat.st_size}:{stat.st_mtime_ns}:h264-aac-renditions-v2'.encode()).hexdigest()
        return path, self.root/key

    async def descriptor(self, sid):
        path, folder = self.folder(sid)
        probe = json.loads(await run_media('/usr/bin/ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)))
        if not has_motion_video(probe):
            raise web.HTTPUnsupportedMediaType(text='文件没有可播放的视频轨')
        videos = [s for s in probe['streams'] if s.get('codec_type')=='video' and not s.get('disposition',{}).get('attached_pic')]
        audio = [s for s in probe['streams'] if s.get('codec_type')=='audio']
        compatible = videos[0].get('codec_name') in ('h264','hevc','vp9') and all(s.get('codec_name') in ('aac','mp3','opus','vorbis') for s in audio)
        return {'mediaKind':'mv', 'transport':'progressive' if compatible else 'hls',
            'playUrl':f'/stream/{sid}' if compatible else f'/mv/{sid}/master.m3u8',
            'mimeType':None if compatible else 'application/x-mpegURL', 'canSeek':True,
            'durationMs':int(float(probe.get('format',{}).get('duration') or 0)*1000),
            'videoCodec':videos[0].get('codec_name'), 'audioTracks':[{'codec':s.get('codec_name'),'index':s.get('index')} for s in audio],
            'fallbackReason':None if compatible else '设备兼容 H.264/AAC 视频转码', 'expiresAt':None}

    async def transcode(self, source, folder):
        async with self.slot:
            folder.mkdir(parents=True, exist_ok=True)
            metadata=json.loads(await run_media('/usr/bin/ffprobe','-v','error','-show_streams','-of','json',str(source)))
            audio_count=sum(s.get('codec_type')=='audio' for s in metadata.get('streams',[]))
            # Keep both original audio streams when present. No guessed vocal labels.
            command = ['/usr/bin/ffmpeg','-hide_banner','-loglevel','error','-y','-threads','2','-i',str(source),
                '-map','0:v:0','-map','0:a?','-c:v','libx264','-preset','veryfast','-crf','23','-threads','2',
                '-vf',"scale=w='min(1280,iw)':h=-2,format=yuv420p",'-profile:v','main','-level:v','3.1','-force_key_frames','expr:gte(t,n_forced*2)',
                '-c:a','aac','-b:a','160k','-ac','2','-ar','48000','-f','hls','-hls_time','2',
                '-hls_list_size','0','-hls_playlist_type','event','-hls_flags','temp_file',
                '-var_stream_map',self.renditions(audio_count),'-master_pl_name','master.m3u8',
                '-hls_segment_filename',str(folder/'segment_%v_%05d.ts'),str(folder/'index_%v.m3u8')]
            proc = await asyncio.create_subprocess_exec(*command,stdout=asyncio.subprocess.DEVNULL,stderr=asyncio.subprocess.DEVNULL)
            try:
                started=time.monotonic()
                while proc.returncode is None:
                    try: await asyncio.wait_for(proc.wait(),1)
                    except asyncio.TimeoutError: pass
                    size=sum(p.stat().st_size for p in folder.iterdir() if p.is_file())
                    if size>self.limit or time.monotonic()-started>3600:
                        raise RuntimeError('Video conversion exceeded resource limit')
                if proc.returncode:
                    raise RuntimeError('Video compatibility conversion failed')
                # Verify output contains a real video stream before recording completion.
                probe=json.loads(await run_media('/usr/bin/ffprobe','-v','error','-show_streams','-of','json',str(folder/'segment_video_00000.ts')))
                if not has_motion_video(probe):raise RuntimeError('Missing converted video stream')
                for i in range(audio_count):
                    if not (folder/f'index_audio{i}.m3u8').is_file():raise RuntimeError('Missing embedded audio rendition')
                (folder/'complete').touch()
                self.prune(folder)
            finally:
                if proc.returncode is None:proc.kill();await proc.wait()

    @staticmethod
    def renditions(audio_count):
        # HLS players may expose only the first audio stream in a muxed TS.
        # Keep each embedded track as a selectable rendition, with no guessed labels.
        audio=[f'a:{i},agroup:audio,default:{"yes" if i==0 else "no"},name:audio{i}' for i in range(audio_count)]
        return ' '.join(audio+['v:0,'+('agroup:audio,' if audio_count else '')+'name:video'])

    def prune(self, current):
        folders=[p for p in self.root.iterdir() if p.is_dir() and not p.is_symlink() and len(p.name)==64
                 and all(c in '0123456789abcdef' for c in p.name)]
        sizes={p:sum(f.stat().st_size for f in p.iterdir() if f.is_file()) for p in folders}
        total=sum(sizes.values())
        for folder in sorted(folders,key=lambda p:p.stat().st_mtime):
            if total<=self.limit:break
            if folder==current or time.time()-folder.stat().st_mtime<3600 or not (folder/'complete').exists():continue
            for file in folder.iterdir():
                if file.is_file() and not file.is_symlink():file.unlink()
            folder.rmdir();total-=sizes[folder]

    async def hls(self, req):
        source, folder = self.folder(int(req.match_info['sid']))
        name=req.match_info['name']
        key=folder.name
        task=self.tasks.get(key)
        if name=='master.m3u8':
            if not (folder/'complete').exists() and (not task or task.done()):
                if sum(not t.done() for t in self.tasks.values())>=2:
                    raise web.HTTPServiceUnavailable(text='视频兼容任务繁忙，请稍后重试')
                task=asyncio.create_task(self.transcode(source,folder));self.tasks[key]=task
                task.add_done_callback(lambda t:t.exception() if not t.cancelled() else None)
            for _ in range(120):
                if task and task.done() and task.exception():
                    raise web.HTTPBadGateway(text='视频兼容转换失败，原文件保持不变')
                if (folder/'master.m3u8').is_file() and (folder/'segment_video_00000.ts').is_file():break
                await asyncio.sleep(.2)
        target=folder/name
        if not target.is_file():raise web.HTTPNotFound()
        os.utime(folder,None)
        return web.FileResponse(target,headers={'Content-Type':'application/vnd.apple.mpegurl' if name.endswith('.m3u8') else 'video/mp2t','Cache-Control':'no-store'})

    async def close(self):
        for task in self.tasks.values():task.cancel()
        await asyncio.gather(*self.tasks.values(),return_exceptions=True)
