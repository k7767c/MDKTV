"""Durable measured pitch references, matched by exact media bytes, not names."""
import asyncio,hashlib,json,math,re,time,uuid
from pathlib import Path
from aiohttp import web,ClientTimeout

METHOD='demucs-htdemucs/torchcrepe-full/acoustic-v1'

def validate(data,digest,track):
    if not isinstance(data,dict):raise ValueError('Invalid score')
    notes=data.get('notes');step=data.get('stepMs');duration=data.get('durationMs')
    if not isinstance(notes,list) or not 50<=len(notes)<=180000:raise ValueError('Invalid notes')
    if type(step) is not int or not 10<=step<=200:raise ValueError('Invalid step')
    if type(duration) is not int or duration<1000 or abs(len(notes)*step-duration)>step*2:raise ValueError('Invalid duration')
    if any(n is not None and (type(n) not in (int,float) or not math.isfinite(n) or not 0<n<128) for n in notes):raise ValueError('Invalid pitch')
    if sum(n is not None for n in notes)<50:raise ValueError('Empty score')
    if data.get('mediaSha256')!=digest or data.get('requestedTrack')!=track or data.get('method')!=METHOD:raise ValueError('Wrong reference')
    if data.get('complete') is not True or type(data.get('timelineVersion')) is not int or data['timelineVersion']<2:raise ValueError('Incomplete reference')
    if type(data.get('audioTrack')) is not int or not 0<=data['audioTrack']<=63 or (track>=0 and track!=data['audioTrack']):raise ValueError('Wrong audio track')
    # An allowlist prevents paths, cookies, account details or provider URLs from
    # accidentally being persisted or served to other devices.
    keys=('notes','stepMs','durationMs','audioTrack','requestedTrack','method','timelineVersion','analysisVersion','mediaSha256','mediaBytes')
    result={k:data[k] for k in keys if k in data}
    result.update(status='ready',complete=True,referenceVersion=5,experimental=True,referenceOrigin='current-media-original')
    return result

class SharedScores:
    def __init__(self,bridge):
        self.b=bridge;self.root=Path(bridge.cfg.get('shared_score_cache_dir',str(bridge.scores.root/'shared')))
        self.root.mkdir(parents=True,exist_ok=True);self.pending={};self.errors={};self.lock=asyncio.Semaphore(1)
        bridge.db.execute('CREATE TABLE IF NOT EXISTS shared_score_media(identity TEXT PRIMARY KEY,digest TEXT NOT NULL)');bridge.db.commit()
    def path(self,digest,track):
        if not re.fullmatch('[0-9a-f]{64}',digest) or not -1<=track<=63:raise ValueError('Invalid reference key')
        return self.root/digest/(str(track)+'.json')
    def read(self,digest,track=-1):
        path=self.path(digest,track)
        try:return validate(json.loads(path.read_text()),digest,track)
        except (OSError,ValueError,TypeError):return None
    async def api(self,req):
        if req.path=='/api/v2/score-cache/status':
            if req.method!='GET':raise web.HTTPMethodNotAllowed(req.method,['GET'])
            return web.json_response({'protocol':1,'durable':True})
        match=re.fullmatch(r'/api/v2/score-cache/([0-9a-f]{64})/(-?\d+)',req.path)
        if not match:raise web.HTTPNotFound()
        digest,track=match[1],int(match[2])
        try:path=self.path(digest,track)
        except ValueError:raise web.HTTPBadRequest(text='Invalid reference key')
        if req.method=='GET':return web.json_response(self.read(digest,track) or {'status':'missing'})
        if req.method!='PUT':raise web.HTTPMethodNotAllowed(req.method,['GET','PUT'])
        try:data=validate(await req.clone(client_max_size=8*1024*1024).json(),digest,track)
        except (ValueError,TypeError):raise web.HTTPBadRequest(text='Invalid measured reference')
        encoded=json.dumps(data,sort_keys=True,separators=(',',':')).encode();revision=hashlib.sha256(encoded).hexdigest()
        path.parent.mkdir(parents=True,exist_ok=True)
        if path.is_file() and path.read_bytes()!=encoded:
            backup=path.with_name(path.stem+'.'+hashlib.sha256(path.read_bytes()).hexdigest()[:16]+'.bak')
            if not backup.exists():backup.write_bytes(path.read_bytes())
        tmp=path.with_suffix('.'+uuid.uuid4().hex+'.tmp');tmp.write_bytes(encoded);tmp.replace(path)
        return web.json_response({'status':'saved','revision':revision})
    async def for_song(self,sid,allow_fingerprint=True):
        # Only fingerprint requested media and only when shared references exist.
        # This does not scan or generate the NAS library.
        if not any(self.root.iterdir()):return None
        row=self.b.db.execute('SELECT guid,data FROM songs WHERE id=?',(sid,)).fetchone()
        if not row:return None
        guid=row['guid'];song=json.loads(row['data']);path=self.b.videos.path(guid) if guid.startswith('video:') else None
        if path:
            stat=path.stat();version=f'{stat.st_size}:{stat.st_mtime_ns}'
        else:version=str(song.get('revision','0'))
        identity=guid+':'+version
        saved=self.b.db.execute('SELECT digest FROM shared_score_media WHERE identity=?',(identity,)).fetchone()
        if saved:
            data=self.read(saved[0])
            if data is None:
                # A single explicitly selected original track is unambiguous;
                # multiple manually prepared tracks require a default choice.
                alternatives=[self.read(saved[0],int(p.stem)) for p in (self.root/saved[0]).glob('*.json') if p.stem.isdigit() and int(p.stem)<=63]
                alternatives=[a for a in alternatives if a is not None]
                if len(alternatives)==1:data=alternatives[0]
            if data:data=dict(data,revision=hashlib.sha256(json.dumps(data,sort_keys=True).encode()).hexdigest())
            return data
        if not allow_fingerprint:return None
        if self.errors.get(identity,0)>time.monotonic():return None
        if identity not in self.pending and len(self.pending)<3:
            self.pending[identity]=asyncio.create_task(self.fingerprint(identity,guid,path))
        if identity in self.pending:return {'status':'preparing','message':'正在核对 NAS 共享评分谱'}
        return None
    async def fingerprint(self,identity,guid,path):
        try:
            async with self.lock:
                digest=hashlib.sha256()
                # Hashing a large recording is also NAS disk/network work. It
                # remains cancellable and yields to playback between small reads.
                async def idle():
                    while getattr(self.b.scores,'playback_active',False):
                        await asyncio.sleep(1)
                await idle()
                chunk=256*1024
                rate=8*1024*1024
                if path:
                    before=path.stat()
                    if before.st_size>8*1024**3 or identity!=guid+f':{before.st_size}:{before.st_mtime_ns}':raise ValueError('Media changed or too large')
                    with path.open('rb') as f:
                        while True:
                            await idle()
                            block=await asyncio.to_thread(f.read,chunk)
                            if not block:break
                            digest.update(block)
                            await asyncio.sleep(len(block)/rate)
                    after=path.stat()
                    if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise ValueError('Media changed')
                else:
                    # Same original stream as the authenticated player endpoint.
                    async with self.b.http.get(self.b.cfg['music_url'].rstrip('/')+'/api/v1/track/stream',params={'guid':self.b.sources.upstream_guid(guid)},headers={'Cookie':'music-token='+self.b.cfg['music_token'],'Accept-Encoding':'identity'},allow_redirects=False,auto_decompress=False,timeout=ClientTimeout(total=600,sock_read=30)) as r:
                        if r.status!=200:raise ValueError('Media unavailable')
                        count=0
                        async for block in r.content.iter_chunked(chunk):
                            await idle()
                            count+=len(block)
                            if count>8*1024**3:raise ValueError('Media too large')
                            digest.update(block)
                            await asyncio.sleep(len(block)/rate)
                        if r.content_length is not None and count!=r.content_length:raise ValueError('Incomplete media')
                self.b.db.execute('INSERT OR REPLACE INTO shared_score_media VALUES (?,?)',(identity,digest.hexdigest()));self.b.db.commit()
        except Exception:
            self.errors={key:until for key,until in self.errors.items() if until>time.monotonic()}
            if len(self.errors)>=128:self.errors.pop(next(iter(self.errors)))
            self.errors[identity]=time.monotonic()+60
        finally:self.pending.pop(identity,None)
    async def close(self):
        tasks=list(self.pending.values())
        for task in tasks:task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
