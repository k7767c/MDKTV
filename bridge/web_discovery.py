"""The Android public charts/classifications; own-library playback only."""
import asyncio
import json
import math
from pathlib import Path
import re
import time
import unicodedata
from aiohttp import ClientError, ClientTimeout, web

CHARTS = {'hot':('热歌榜',3778678),'new':('新歌榜',3779629),
          'rising':('飙升榜',19723756),'original':('原创榜',2884035)}
DISCOVERY_BUDGET_SECONDS = 12

def normalized(value):
    return ''.join(c for c in unicodedata.normalize('NFKC',str(value)).casefold() if c.isalnum())

def title_key(value):
    value = re.sub(r'\((?:mtv|mv)\)','',str(value),flags=re.I)
    value = re.sub(r'[-－](?:国语|粤语|英语|台语|闽南语)(?:[-－](?:流行|经典|儿歌|民歌|摇滚))?$','',value)
    return normalized(value)

def artist_key(value):
    value = re.sub(r'^\s*\d{2,}[.、_-]\s*','',str(value))
    return re.sub(r'[\s·・.\-_\x27’]','',unicodedata.normalize('NFKC',value).casefold())

def people(value):
    return frozenset(normalized(v) for v in re.split(r'\s*[/、&,;；]\s*',str(value)))

def parse_chart(data,key):
    if not isinstance(data,dict):raise ValueError('invalid chart')
    playlist = data.get('result') or data.get('playlist') or {}
    if not isinstance(playlist,dict) or data.get('code') != 200 or str(playlist.get('id')) != str(CHARTS[key][1]):
        raise ValueError('invalid chart')
    rows=[]
    tracks=playlist.get('tracks',[])
    if not isinstance(tracks,list):raise ValueError('invalid chart tracks')
    for i,item in enumerate(tracks[:200],1):
        if not isinstance(item,dict):raise ValueError('invalid chart row')
        artists=item.get('artists') or item.get('ar') or []
        if not isinstance(artists,list) or any(not isinstance(a,dict) for a in artists):raise ValueError('invalid chart artist')
        if any(not isinstance(a.get('name'),str) or not a['name'].strip() for a in artists):raise ValueError('invalid chart artist')
        names=[a['name'].strip() for a in artists]
        if not item.get('name') or not names:raise ValueError('invalid chart row')
        rows.append({'rank':i,'title':str(item['name']),'artist':' / '.join(names),'artists':names})
    if not rows:raise ValueError('empty chart')
    return {'key':key,'name':CHARTS[key][0],'rows':rows,'updated':playlist.get('updateTime',0),
            'source':'网易云音乐','sourceUrl':'https://music.163.com/discover/toplist?id='+str(CHARTS[key][1]),'fetched':time.time()}

class WebDiscovery:
    def __init__(self,bridge):
        self.b=bridge
        self.root=bridge.cache.parent/'public-discovery'
        self.memory={}
        self.locks={}
        self.index_token=None
        self.index={}

    def cached(self,key):
        if key in self.memory:return self.memory[key]
        try:
            data=json.loads((self.root/(key+'.json')).read_text(encoding='utf-8'))
            if not isinstance(data,dict) or type(data.get('fetched')) not in (int,float) or not math.isfinite(data['fetched']):return None
            if key.startswith('chart-'):
                if not isinstance(data.get('rows'),list) or not data['rows'] or any(not isinstance(row,dict) or
                    not isinstance(row.get('title'),str) or not row['title'].strip() or not isinstance(row.get('artists'),list) or not row['artists'] or
                    any(not isinstance(a,str) or not a.strip() for a in row['artists']) for row in data['rows']):return None
            elif not isinstance(data.get('names'),list) or any(not isinstance(name,str) or not name.strip() for name in data['names']):return None
            self.memory[key]=data
            return data
        except (OSError,ValueError):return None

    def save(self,key,data):
        self.memory[key]=data
        try:
            self.root.mkdir(parents=True,exist_ok=True)
            temp=self.root/(key+'.new')
            temp.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
            temp.replace(self.root/(key+'.json'))
        except OSError:pass

    async def get(self,path,params):
        async with self.b.http.get('https://music.163.com'+path,params=params,
                headers={'Referer':'https://music.163.com/'},allow_redirects=False,timeout=ClientTimeout(total=12)) as response:
            if response.status != 200:raise ValueError('discovery unavailable')
            payload=bytearray()
            async for chunk in response.content.iter_chunked(65536):
                payload.extend(chunk)
                if len(payload)>4*1024*1024:raise ValueError('discovery too large')
            return json.loads(payload)

    def match(self,data):
        token=self.b.catalog.queries.token
        if self.index_token != token:
            index={}
            for row in self.b.db.execute('SELECT s.id,s.data FROM songs s JOIN current_guids c ON c.guid=s.guid'):
                song=json.loads(row['data'])
                identity=(title_key(song.get('title','')),people(song.get('artist','')))
                index.setdefault(identity,[]).append(dict(song,id=row['id'],song_id=row['id']))
            self.index=index
            self.index_token=token
        return dict(data,rows=[dict(row,matches=self.index.get((title_key(row['title']),frozenset(normalized(v) for v in row['artists'])),[])) for row in data['rows']],catalogToken=token)

    async def chart(self,key,force=False,cached_only=False):
        if key not in CHARTS:raise web.HTTPBadRequest(text='未知榜单')
        if cached_only:
            cached=self.cached('chart-'+key)
            if cached:return self.match(dict(cached,cached=True,stale=time.time()-cached['fetched']>=3600))
            raise web.HTTPNotFound(text='尚无榜单缓存')
        try:
            async with asyncio.timeout(DISCOVERY_BUDGET_SECONDS), self.locks.setdefault('chart-'+key,asyncio.Lock()):
                cached=self.cached('chart-'+key)
                stale=bool(cached and time.time()-cached['fetched']>=3600)
                if cached and not force and not stale:return self.match(dict(cached,cached=True,stale=False))
                data=parse_chart(await self.get('/api/playlist/detail',{'id':CHARTS[key][1]}),key)
                self.save('chart-'+key,data)
                return self.match(dict(data,cached=False,stale=False))
        except (ValueError,KeyError,TypeError,ClientError,asyncio.TimeoutError):
            cached=self.cached('chart-'+key)
            if cached:return self.match(dict(cached,cached=True,stale=True))
            raise web.HTTPBadGateway(text='在线榜单暂时不可用，请稍后刷新。') from None

    async def artists(self,area,kind,force=False,cached_only=False):
        if area not in (-1,7,96,8,16,0) or kind not in (-1,1,2,3):raise web.HTTPBadRequest(text='未知歌手分类')
        key=f'artists-{area}-{kind}'
        cached=self.cached(key)
        stale=bool(cached and time.time()-cached['fetched']>=86400)
        if cached and (cached_only or not force and not stale):return dict(cached,cached=True,stale=stale)
        if cached_only:raise web.HTTPNotFound(text='尚无歌手分类缓存')
        # 'All' must not depend on a remote classification request.
        if area == -1 and kind == -1:return {'names':[],'complete':True,'all':True,'cached':False,'stale':False}
        names={}
        try:
            async with asyncio.timeout(DISCOVERY_BUDGET_SECONDS), self.locks.setdefault(key,asyncio.Lock()):
                cached=self.cached(key)
                stale=bool(cached and time.time()-cached['fetched']>=86400)
                if cached and not force and not stale:return dict(cached,cached=True,stale=False)
                complete=False
                for page in range(30):
                    data=await self.get('/api/v1/artist/list',{'area':area,'type':kind,'initial':-1,'offset':page*200,'limit':200})
                    if not isinstance(data,dict) or data.get('code') != 200 or not isinstance(data.get('artists'),list):raise ValueError('invalid artists')
                    rows=data['artists']
                    old=len(names)
                    for row in rows:
                        if not isinstance(row,dict) or not isinstance(row.get('alias') or [],list):raise ValueError('invalid artist')
                        for name in [row.get('name',''),*(row.get('alias') or [])]:
                            if not isinstance(name,str):raise ValueError('invalid artist name')
                            normalized_name=artist_key(name)
                            if normalized_name:names.setdefault(normalized_name,len(names))
                    if not data.get('more') or not rows:complete=True;break
                    if len(names)==old:break
                result={'names':list(names),'complete':complete,'fetched':time.time(),'source':'网易云音乐'}
                if complete:self.save(key,result)
                return dict(result,cached=False,stale=False)
        except (ValueError,KeyError,TypeError,ClientError,asyncio.TimeoutError):
            cached=self.cached(key)
            if cached:return dict(cached,cached=True,stale=True)
            if names:return {'names':list(names),'complete':False,'fetched':time.time(),
                             'source':'网易云音乐','cached':False,'stale':False}
            raise web.HTTPBadGateway(text='歌手分类暂时不可用，可先查看全部歌手。') from None

    async def api(self,request):
        if request.method != 'GET':raise web.HTTPMethodNotAllowed(request.method,['GET'])
        query=request.query
        if request.path == '/api/web/discovery/charts':
            result=await self.chart(query.get('key','hot'),query.get('refresh')=='1',query.get('cached')=='1')
        elif request.path == '/api/web/discovery/artists':
            try:area,kind=int(query.get('area',-1)),int(query.get('type',-1))
            except ValueError:raise web.HTTPBadRequest(text='歌手分类无效') from None
            result=await self.artists(area,kind,query.get('refresh')=='1',query.get('cached')=='1')
        else:raise web.HTTPNotFound()
        return web.json_response(result,headers={'Cache-Control':'no-store'})
