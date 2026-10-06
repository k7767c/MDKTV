"""Recording-aware lyric lookup for missing lyrics and live albums."""
import asyncio
import hashlib
import html
import json
import re
import time
import unicodedata


def norm(value):
    value=html.unescape(re.sub(r'<[^>]*>','',str(value or '')))
    return ''.join(c for c in unicodedata.normalize('NFKC',value).casefold() if c.isalnum())


def live(song):
    return bool(re.search(r'live|演唱会|现场|演唱會|現場',song.get('title','')+' '+song.get('album',''),re.I))


def recording_match(song, candidate):
    try:
        if float(song.get('duration') or 0)<=0 or abs(float(song['duration'])-float(candidate.get('duration') or 0))>2:return False
    except (ValueError,TypeError):return False
    if not norm(song.get('artist')) or norm(song.get('artist'))!=norm(candidate.get('artist')):return False
    album=norm(song.get('album'));other=norm(candidate.get('album'))
    compatible=bool(album and other and (album==other or (min(len(album),len(other))>=6 and (album in other or other in album))))
    if album and other and not compatible:return False
    if norm(song.get('title'))==norm(candidate.get('title')):return True
    # Only a trailing Live label may be absent from NAS tags. Album evidence is
    # required; never erase arbitrary versions, medley labels or language markers.
    strip=lambda s:re.sub(r'\s*[（(]live[)）]\s*$','',s,flags=re.I)
    return compatible and live(song) and norm(strip(song.get('title','')))==norm(strip(candidate.get('title','')))


def timed_lrc(content, duration):
    """Convert KRC relative word anchors to enhanced LRC consumed by Android."""
    def tag(ms,opening='[',closing=']'):
        return f'{opening}{ms//60000:02d}:{ms%60000/1000:06.3f}{closing}'
    output=[]
    for line in content.splitlines():
        match=re.match(r'^\[(\d+),(\d+)\](.*)$',line)
        if not match:continue
        start,length=int(match[1]),int(match[2]);body=match[3]
        if start<0 or start>duration*1000+2000 or length<0:continue
        words=list(re.finditer(r'<(\d+),(\d+),\d+>',body))
        if not words:continue
        pieces=[]
        for index,word in enumerate(words):
            end=words[index+1].start() if index+1<len(words) else len(body)
            at=start+int(word[1]);text=body[word.end():end]
            if at>start+length+2000:raise ValueError('invalid word timing')
            pieces.append(tag(at,'<','>')+text)
        # Preserve the final word end, so a long following gap doesn't stretch it.
        last=words[-1];end=start+int(last[1])+int(last[2])
        output.append(tag(start)+''.join(pieces)+tag(end,'<','>'))
    if not output:raise ValueError('no timed lyrics')
    return '\n'.join(output)


class OnlineLyrics:
    def __init__(self, bridge):
        self.bridge=bridge
        self.jobs={}
        self.misses={}
        self.slots=asyncio.Semaphore(2)

    def path(self,sid):return self.bridge.scores.folder(sid)/'online-lyrics-v1.json'

    async def get(self,sid,fallback):
        song=self.bridge.song(sid)
        stamp=hashlib.sha256(json.dumps({k:song.get(k) for k in ('title','artist','album','duration')},sort_keys=True).encode()).hexdigest()
        try:
            cached=json.loads(self.path(sid).read_text())
            if cached.get('recordingStamp')==stamp:return cached
        except (OSError,ValueError):pass
        if not self.bridge.cfg.get('online_tracks_enabled',False):return fallback
        if fallback.get('content') and not live(song):return fallback
        if time.time()-self.misses.get(sid,0)<300:return fallback
        task=self.jobs.get(sid)
        if task is None or task.done():
            self.jobs={k:v for k,v in self.jobs.items() if not v.done()}
            if len(self.jobs)>=8:return fallback
            task=asyncio.create_task(self.lookup(sid,song,stamp));self.jobs[sid]=task
        try:return await asyncio.wait_for(asyncio.shield(task),24) or fallback
        except asyncio.TimeoutError:return fallback

    async def lookup(self,sid,song,stamp):
        try:
            async with self.slots:
                keyword=song.get('title','')+' '+song.get('artist','')+(' Live' if live(song) else '')
                data=await self.bridge.online_tracks.call('search',{'keyword':keyword})
                choices=[s for s in data.get('candidates',[]) if recording_match(song,s)]
                # Different equally eligible recordings remain ambiguous.
                if len({s.get('hash') for s in choices})!=1:return None
                response=await self.bridge.online_tracks.call('lyrics',choices[0])
                if response.get('status')!='ok':return None
                text=timed_lrc(response.get('content',''),float(song['duration']))
                result={'content':text,'synced':True,'offset':0,'source':'酷狗 · 同录音版本歌词',
                        'recordingStamp':stamp,'providerId':response.get('providerId'),
                        'matchedAlbum':choices[0].get('album'),'matchedDuration':choices[0].get('duration')}
                target=self.path(sid);target.parent.mkdir(parents=True,exist_ok=True)
                temp=target.with_suffix('.tmp');temp.write_text(json.dumps(result,ensure_ascii=False));temp.replace(target)
                return result
        except Exception:return None
        finally:self.misses[sid]=time.time()

    async def close(self):
        for task in self.jobs.values():task.cancel()
        await asyncio.gather(*self.jobs.values(),return_exceptions=True)
