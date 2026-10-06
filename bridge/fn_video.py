"""fnOS Video 0.9.8 protocol, independently implemented from installed web client.

The public web-client signing constant is not a user credential. Authentication
still requires the user's independent Video token in Authorization.
"""
import asyncio
import hashlib
import json
import secrets
import time
from urllib.parse import urlsplit, urlencode, unquote
from aiohttp import ClientError
from source_auth import MusicSource, SourceError


def video_signature(path, method, body=None, params=None, nonce=None, timestamp=None):
    public_key = bytes(n ^ 169 for n in [152,159,234,234,236,235,154,237,132,232,235,157,155,132,153,158,158,237,132,154,159,232,152,132,239,154,156,156,154,155,157,236,157,155,154,158]).decode()
    digest = lambda s: hashlib.md5(s.encode('utf-8')).hexdigest()
    if method == 'GET':
        content = unquote(urlencode(sorted((params or {}).items())).replace('+','%20'))
    else:
        content = '' if body is None else json.dumps(body, ensure_ascii=False, separators=(',',':'))
    nonce = nonce or str(secrets.randbelow(900000)+100000)
    timestamp = timestamp or str(int(time.time()*1000))
    sign = digest('_'.join(['NDzZTVxnRKP8Z0jXg1VAMonaG8akvh',path,nonce,timestamp,digest(content),public_key]))
    return f'nonce={nonce}&timestamp={timestamp}&sign={sign}'


class VideoSource(MusicSource):
    kind = 'video'
    suffix = 'v'

    async def raw(self, path, *, token='', body=None, params=None, url=None):
        base = url or self.data.get('url')
        if not base:
            raise SourceError('AUTH_REQUIRED','请先连接飞牛影视')
        version = 'v2' if path == 'user/loginByPassword' else 'v1'
        address = base+'/api/'+version+'/'+path
        method = 'POST' if body is not None else 'GET'
        headers = {'Authorization':token, 'authx':video_signature(urlsplit(address).path, method, body, params)}
        encoded = None if body is None else json.dumps(body,ensure_ascii=False,separators=(',',':')).encode()
        if encoded is not None: headers['Content-Type']='application/json'
        try:
            async with self.owner.b.http.request(method, address, params=params, data=encoded,
                    headers=headers, allow_redirects=False) as response:
                if response.status == 401:raise SourceError('AUTH_REQUIRED','影视授权已失效')
                if response.status != 200:raise SourceError('SOURCE_OFFLINE','影视接口暂不可用',True)
                result = await response.json()
        except (ClientError, asyncio.TimeoutError, ValueError):
            raise SourceError('SOURCE_OFFLINE','影视连接失败或返回格式异常',True) from None
        if result.get('code') == -2:raise SourceError('AUTH_REQUIRED','影视授权已失效')
        if result.get('code') == -15:raise SourceError('INVALID_CREDENTIALS','影视账号或密码不正确')
        if result.get('code') != 0:
            # Exact business-code classification is version specific. Do not
            # relogin on an arbitrary nonzero response/signature failure.
            raise SourceError('VIDEO_API_ERROR', '影视接口错误，代码 '+str(result.get('code')))
        return result['data']

    async def authenticate(self, url, username, digest, device):
        data = await self.raw('user/loginByPassword', url=url, body={
            'username':username,'password':digest,'app_name':'trimemedia-web'})
        token = data.get('token','')
        if not token or any(c in token for c in '\r\n'):
            raise SourceError('INVALID_RESPONSE','影视登录未返回有效会话')
        return token

    async def libraries(self):
        rows = await self.call('mdb/list')
        if not isinstance(rows,list):
            raise SourceError('INVALID_RESPONSE','影视媒体库响应不符合已核实版本')
        return [{'id':r['guid'],'name':r.get('name') or r.get('title') or r['guid'],
                 'selected':r['guid'] in self.data.get('libraries',[])} for r in rows]
