"""Private loopback provider access; selected songs only, no model fallback."""
import asyncio
import html
import hashlib
import json
import os
import re
import shutil
import time
import unicodedata
from urllib.parse import urlsplit, urlunsplit
from aiohttp import ClientTimeout, ClientError, web


class OnlineTracks:
    def __init__(self, bridge):
        self.bridge = bridge
        self.jobs = {}
        self.slots = asyncio.Semaphore(1)

    @staticmethod
    def matches(song, candidate):
        def norm(value):
            text=html.unescape(re.sub(r'<[^>]*>','',str(value or '')))
            return ''.join(c for c in unicodedata.normalize('NFKC',text).casefold() if c.isalnum())
        try:
            duration=float(song.get('duration') or 0)
            other=float(candidate.get('duration') or 0)
            return (duration>0 and other>0 and abs(duration-other)<=2 and
                    bool(norm(song.get('title'))) and bool(norm(song.get('artist'))) and
                    norm(song.get('title'))==norm(candidate.get('title')) and
                    norm(song.get('artist'))==norm(candidate.get('artist')))
        except (ValueError,TypeError):return False

    def state(self, sid):
        try:return json.loads((self.bridge.scores.folder(sid)/'online-match.json').read_text())
        except (OSError,ValueError):return None

    def save(self, sid, data):
        folder=self.bridge.scores.folder(sid);folder.mkdir(parents=True,exist_ok=True)
        value=dict(data,checkedAt=time.time())
        tmp=folder/'online-match.tmp';tmp.write_text(json.dumps(value));tmp.replace(folder/'online-match.json')
        return value

    def request(self, sid):
        if self.bridge.song(sid).get('media_kind') == 'mv':
            return {'status':'unavailable','reason':'embedded_mv_audio',
                    'message':'MV 使用文件内置音轨；不会匹配其他版本的伴奏，评分谱独立于音轨'}
        if not self.bridge.cfg.get('online_tracks_enabled',False):
            return {'status':'unavailable','message':'暂无匹配的评分谱，可继续自由演唱；NAS 自动生成已关闭'}
        previous=self.state(sid)
        if previous and previous.get('reason')=='cached_accompaniment' and not (self.bridge.scores.folder(sid)/'accompaniment.m4a').is_file():previous=None
        if previous and time.time()-previous.get('checkedAt',0)<previous.get('retrySeconds',600):return previous
        old=self.jobs.get(sid)
        if old is not None and not old.done():
            return {'status':'queued','message':'正在匹配在线资源，歌曲可正常播放'}
        # Finished tasks are not retained for the entire library lifetime.
        self.jobs={key:task for key,task in self.jobs.items() if not task.done()}
        if len(self.jobs)>=32:return {'status':'busy','message':'等待匹配已点歌曲，歌曲可正常播放'}
        self.jobs[sid]=asyncio.create_task(self.match(sid))
        return {'status':'queued','message':'正在匹配在线资源，歌曲可正常播放'}

    async def match(self, sid):
        async with self.slots:
            try:
                song=self.bridge.song(sid)
                if song.get('media_kind') == 'mv':return
                result=await self.call('search',{'keyword':song.get('title','')+' '+song.get('artist','')})
                if result.get('status')=='login_required':
                    self.save(sid,{'status':'unavailable','reason':'login_required','retrySeconds':60,
                                  'message':'请在音源登录页面登录酷狗；可继续自由演唱'})
                    return
                if result.get('status')!='ok':raise ValueError('provider unavailable')
                candidates=[c for c in result.get('candidates',[]) if self.matches(song,c)]
                if not candidates:
                    self.save(sid,{'status':'unavailable','reason':'no_exact_match','retrySeconds':86400,
                                  'message':'未找到歌名、歌手和时长一致的在线资源，可继续自由演唱'})
                    return
                # Never resolve a fuzzy match or substitute a different recording/version.
                resolved=await self.call('resolve',candidates[0])
                if resolved.get('status')=='resource':
                    await self.download(sid,song,resolved)
                    self.save(sid,{'status':'unavailable','reason':'cached_accompaniment','retrySeconds':31536000,
                                  'message':'在线伴奏已缓存；暂无评分谱，可自由演唱'})
                else:
                    self.save(sid,{'status':'unavailable','reason':resolved.get('reason','no_resource'),
                                  'retrySeconds':600,'message':'已匹配歌曲，音源暂未提供可用的双音轨或评分谱；可自由演唱'})
            except Exception:
                self.save(sid,{'status':'unavailable','reason':'provider_error','retrySeconds':300,
                              'message':'在线资源暂不可用，可继续自由演唱'})

    @staticmethod
    def media_url(value):
        url=urlsplit(value)
        if url.scheme not in ('http','https') or url.hostname not in ('fsandroid.kugou.com','fsmobile.tx.kugou.com') or url.username or url.password or url.port not in (None,80,443):
            raise ValueError('unexpected media host')
        return urlunsplit(('https',url.hostname,url.path,url.query,''))

    async def process(self, *args):
        proc=await asyncio.create_subprocess_exec(*args,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
        try:
            out,_=await asyncio.wait_for(proc.communicate(),90)
            if proc.returncode:raise ValueError('invalid media')
            return out
        finally:
            if proc.returncode is None:
                proc.kill();await proc.wait()

    async def download(self, sid, song, resource):
        duration=float(song.get('duration') or 0)
        if not 0<duration<=1800 or abs(duration-float(resource.get('duration') or 0))>2:
            raise ValueError('different recording duration')
        size=int(resource.get('size') or 0)
        if not 0<size<=64*1024**2 or not re.fullmatch('[0-9a-fA-F]{32}',resource.get('hash','')):
            raise ValueError('invalid resource')
        folder=self.bridge.scores.folder(sid);folder.mkdir(parents=True,exist_ok=True)
        if shutil.disk_usage(folder).free<512*1024**2:raise ValueError('insufficient cache space')
        source=folder/'online-download.mkv';target=folder/'online-accompaniment.m4a'
        try:
            downloaded=False
            for value in resource.get('urls',[])[:2]:
                try:
                    digest=hashlib.md5();total=0
                    async with self.bridge.http.get(self.media_url(value),timeout=ClientTimeout(total=120),allow_redirects=False) as r:
                        if r.status!=200:continue
                        with source.open('wb') as out:
                            async for chunk in r.content.iter_chunked(128*1024):
                                total+=len(chunk)
                                if total>size or total>64*1024**2:raise ValueError('oversized resource')
                                digest.update(chunk);out.write(chunk)
                    if total!=size or digest.hexdigest()!=resource['hash'].lower():raise ValueError('incomplete resource')
                    downloaded=True;break
                except (ClientError,OSError,asyncio.TimeoutError):continue
            if not downloaded:raise ValueError('download unavailable')
            info=json.loads(await self.process('/usr/bin/ffprobe','-v','error','-show_streams','-show_format','-of','json',str(source)))
            if abs(float(info['format']['duration'])-duration)>2:raise ValueError('truncated resource')
            tracks=[s for s in info.get('streams',[]) if s.get('codec_type')=='audio']
            backing=[s for s in tracks if s.get('tags',{}).get('title')=='伴奏']
            if len(backing)!=1 or not any(s.get('tags',{}).get('title')=='人声' for s in tracks):
                raise ValueError('unverified track roles')
            # Container/audio conversion only: no separation, transcription or pitch model.
            await self.process('/usr/bin/ffmpeg','-v','error','-y','-i',str(source),'-map','0:'+str(backing[0]['index']),
                               '-vn','-c:a','aac','-b:a','128k','-movflags','+faststart',str(target))
            if target.stat().st_size<1024:raise ValueError('empty accompaniment')
            target.replace(folder/'accompaniment.m4a');os.utime(folder,None)
            self.bridge.scores.prune(folder)
        finally:
            source.unlink(missing_ok=True);target.unlink(missing_ok=True)

    async def close(self):
        for task in self.jobs.values():task.cancel()
        await asyncio.gather(*self.jobs.values(),return_exceptions=True)

    async def call(self, action, data=None):
        if not self.bridge.cfg.get('online_tracks_enabled', False):
            return {'status':'not_configured','message':'在线音源尚未配置'}
        try:
            async with self.bridge.http.post('http://127.0.0.1:8095/'+action,
                    json=data or {}, timeout=ClientTimeout(total=25), allow_redirects=False) as r:
                if r.status != 200:
                    return {'status':'error','message':'音源接口暂不可用，请稍后重试'}
                return await r.json()
        except (OSError, ClientError, asyncio.TimeoutError):
            return {'status':'error','message':'音源服务暂不可用'}

    async def login(self, action):
        if action not in ('status','login','poll','logout'):
            raise web.HTTPNotFound()
        data=await self.call(action)
        # Explicit allowlist: upstream authentication never reaches the browser.
        return web.json_response({k:v for k,v in data.items() if k in
            ('status','message','configured','image','expiresIn')},headers={'Cache-Control':'no-store'})
