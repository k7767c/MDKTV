"""Independent upstream sessions, encrypted at rest; never returned to players."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import secrets
import time
from urllib.parse import urlsplit, urlunsplit

from aiohttp import web, ClientError
from cryptography.fernet import Fernet


class SourceError(Exception):
    def __init__(self, code, message, retryable=False):
        self.code, self.message, self.retryable = code, message, retryable
        super().__init__(message)


def source_url(value, suffix='music'):
    value = str(value).strip()
    if any(ord(c) < 32 for c in value):
        raise SourceError('INVALID_ADDRESS', '地址不能含控制字符')
    u = urlsplit(value)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment:
        raise SourceError('INVALID_ADDRESS', '请输入不含账号和查询参数的 HTTP/HTTPS 地址')
    try:
        u.port
    except ValueError:
        raise SourceError('INVALID_ADDRESS', '端口无效') from None
    path = u.path.rstrip('/')
    if not path.endswith('/'+suffix):
        path += '/'+suffix
    return urlunsplit((u.scheme, u.netloc, path, '', ''))


def private_write(path, data):
    path = Path(path); path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = path.with_name(path.name+'.new')
    with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as out:
        out.write(data); out.flush(); os.fsync(out.fileno())
    os.chmod(tmp, 0o600); tmp.replace(path)


class SourceVault:
    def __init__(self, folder):
        self.folder = Path(folder)
        self.keyfile = self.folder/'keys'/'sources.key'
        self.file = self.folder/'sources.enc'
        if not self.keyfile.exists():
            if self.file.exists():
                raise RuntimeError('Source vault key missing; restore key backup')
            private_write(self.keyfile, Fernet.generate_key())
        self.fernet = Fernet(self.keyfile.read_bytes())

    def load(self):
        return json.loads(self.fernet.decrypt(self.file.read_bytes())) if self.file.exists() else {}

    def save(self, data):
        private_write(self.file, self.fernet.encrypt(json.dumps(data, ensure_ascii=False).encode()))


class MusicSource:
    kind = 'music'
    suffix = 'music'

    def __init__(self, owner, data):
        self.owner, self.data = owner, data
        self.lock = asyncio.Lock()
        self.generation = 0
        self.state = 'unverified' if data.get('token') else 'unconfigured'
        self.error = ''

    def status(self):
        return {k: self.data.get(k) for k in ('url', 'username', 'serverId', 'remember', 'autoLogin', 'migrated')} | {
            'id': self.kind, 'state': self.state, 'error': self.error}

    async def raw(self, path, *, token='', body=None, params=None, url=None):
        base = url or self.data.get('url')
        if not base:
            raise SourceError('AUTH_REQUIRED', '请先连接飞牛音乐')
        try:
            async with self.owner.b.http.request('GET' if body is None else 'POST', base+'/api/v1/'+path,
                    params=params, json=body, headers={'Cookie': 'music-token='+token} if token else {},
                    allow_redirects=False) as r:
                if r.status == 401:
                    raise SourceError('AUTH_REQUIRED', '音乐授权已失效')
                if r.status != 200:
                    raise SourceError('SOURCE_OFFLINE', '音乐接口暂不可用', True)
                result = await r.json()
        except (ClientError, asyncio.TimeoutError, ValueError):
            raise SourceError('SOURCE_OFFLINE', '音乐连接失败或响应格式异常', True) from None
        code = result.get('code')
        message = str(result.get('msg') or result.get('message') or '').upper()
        if code in (401, '401') or 'INVALID TOKEN' in message:
            raise SourceError('AUTH_REQUIRED', '音乐授权已失效')
        if code == 120001:
            raise SourceError('INVALID_CREDENTIALS', '账号或密码不正确')
        if code != 0:
            raise SourceError('UPSTREAM_ERROR', '音乐接口返回错误，请检查账号权限')
        return result['data']

    async def authenticate(self, url, username, digest, device):
        d = await self.raw('user/password-login', url=url, body={
            'username': username, 'password': digest, 'deviceId': device})
        token = d.get('userToken', '')
        if not token or any(c in token for c in '\r\n;'):
            raise SourceError('INVALID_RESPONSE', '音乐登录未返回有效会话')
        return token

    async def login(self, value):
        url = source_url(value.get('url', ''), self.suffix)
        username = str(value.get('username', '')).strip()
        password = str(value.get('password', ''))
        if not username or not password:
            raise SourceError('INVALID_CREDENTIALS', '请输入账号和密码')
        if url.startswith('http:') and not value.get('allowHttp'):
            raise SourceError('INSECURE_TRANSPORT', '内网 HTTP 不加密，请确认信任此网络或改用 HTTPS')
        self.generation += 1; generation = self.generation
        self.state = 'connecting'
        async with self.lock:
            device = self.data.get('deviceId') or 'feiniu-karaoke-'+secrets.token_hex(16)
            digest = hashlib.sha256(password.encode('utf-8')).hexdigest()
            try:
                token = await self.authenticate(url, username, digest, device)
                if generation != self.generation:
                    raise SourceError('STALE_SESSION', '连接已变更，请重试')
                old = self.data
                same = old.get('url') == url and old.get('username') == username
                self.data = {'url':url, 'username':username, 'deviceId':device, 'token':token,
                    'serverId':old.get('serverId') if old.get('url') == url else secrets.token_hex(16),
                    'scope':old.get('scope') if same else secrets.token_hex(16),
                    'remember': bool(value.get('remember', True)), 'autoLogin': bool(value.get('autoLogin', False))}
                if self.kind == 'video' and same:
                    self.data['libraries'] = old.get('libraries', [])
                if self.data['autoLogin']:
                    self.data['remember'] = True
                    self.data['digest'] = digest
                self.state = 'authenticated'; self.error = ''
                self.owner.persist()
            except SourceError as e:
                if generation == self.generation:
                    self.state = 'login_failed'; self.error = e.message
                raise

    async def call(self, path, params=None, *, body=None):
        if body is not None and (self.kind != 'video' or path not in ('item/list', 'play/info')):
            raise SourceError('INVALID_REQUEST', '只允许重试已核实的只读影视接口')
        generation = self.generation
        token = self.data.get('token', '')
        if not token:
            raise SourceError('AUTH_REQUIRED', '请登录媒体来源')
        try:
            result = await self.raw(path, token=token, params=params, body=body)
        except SourceError as e:
            if generation != self.generation:
                raise SourceError('STALE_SESSION', '连接已变更') from None
            if e.code != 'AUTH_REQUIRED':
                self.state = 'temporarily_offline'; self.error = e.message; raise
            async with self.lock:
                if generation != self.generation:
                    raise SourceError('STALE_SESSION', '连接已变更')
                if token == self.data.get('token'):
                    if not self.data.get('autoLogin') or not self.data.get('digest'):
                        self.state = 'reauth_required'; self.error = '请重新登录'; raise
                    self.state = 'reauthenticating'
                    try:
                        new_token = await self.authenticate(self.data['url'], self.data['username'], self.data['digest'], self.data['deviceId'])
                        if generation != self.generation:
                            raise SourceError('STALE_SESSION', '连接已变更')
                        self.data['token'] = new_token; self.owner.persist()
                    except SourceError as error:
                        if generation == self.generation:
                            self.state = 'reauth_required'; self.error = error.message
                            if not error.retryable:
                                self.data.pop('digest', None); self.data['autoLogin'] = False; self.owner.persist()
                        raise
            # A read is retried once only. Never retry a queue/playback mutation here.
            result = await self.raw(path, token=self.data.get('token',''), params=params, body=body)
        if generation != self.generation:
            raise SourceError('STALE_SESSION', '连接已变更')
        self.state = 'authenticated'; self.error = ''
        return result

    def logout(self):
        self.generation += 1
        self.data = {'serverId': self.data.get('serverId'), 'remember':True}
        self.state = 'unconfigured'; self.error = ''; self.owner.persist()


class SourceManager:
    def __init__(self, bridge, state):
        self.b = bridge
        self.vault = SourceVault(Path(state).resolve().parent/'source-secrets')
        saved = self.vault.load()
        legacy = bridge.cfg
        if 'music' not in saved and legacy.get('music_token'):
            saved['music'] = {'url':source_url(legacy['music_url']), 'token':legacy['music_token'],
                'serverId':secrets.token_hex(16), 'scope':'legacy', 'migrated':True, 'remember':True}
        self.music = MusicSource(self, saved.get('music', {}))
        from fn_video import VideoSource
        self.video = VideoSource(self, saved.get('video', {}))
        from quark_source import QuarkSource
        self.quark = QuarkSource(self, saved.get('quark', {}))
        self.persist()

    def persist(self):
        self.vault.save({s.kind:s.data if s.data.get('remember') else {} for s in (self.music,self.video,self.quark)})
        # Legacy streaming adapters see only the currently selected account.
        self.b.cfg['music_url'] = self.music.data.get('url', '')
        self.b.cfg['music_token'] = self.music.data.get('token', '')

    def music_guid(self, upstream):
        scope = self.music.data.get('scope', 'legacy')
        return upstream if scope == 'legacy' else 'music:'+scope+':'+upstream

    def upstream_guid(self, guid):
        if not self.music.data.get('token'):
            raise web.HTTPForbidden(text='音乐来源已退出登录')
        scope = self.music.data.get('scope', 'legacy')
        if guid.startswith('music:'):
            _, stored_scope, upstream = guid.split(':', 2)
            if stored_scope != scope:
                raise web.HTTPForbidden(text='此歌曲属于其他账号，请从当前曲库重新点歌')
            return upstream
        if scope != 'legacy':
            raise web.HTTPForbidden(text='旧来源已断开，请从当前曲库重新点歌')
        return guid

    async def api(self, req):
        action = req.path.removeprefix('/api/v2/sources').strip('/')
        if action.startswith('quark/'):
            return await self.quark.api(req, action.removeprefix('quark/'))
        if req.method == 'GET' and not action:
            return web.json_response({'sources':[self.music.status(), self.video.status(), self.quark.status()], 'admin':self.b.is_admin_session(req)}, headers={'Cache-Control':'no-store'})
        if not self.b.is_admin_session(req):
            raise web.HTTPUnauthorized(text='修改来源需要应用管理账号或飞牛管理员身份，普通点歌权限不能修改来源')
        if req.method == 'GET' and action == 'video/libraries':
            return web.json_response({'items':await self.video.libraries()})
        if req.method != 'POST':
            raise web.HTTPMethodNotAllowed(req.method, ['POST'])
        try:
            if action == 'video/login':
                await self.video.login(await req.json())
                with self.b.db:
                    self.b.db.execute("DELETE FROM current_guids WHERE guid LIKE 'fnvideo:%'")
                await self.b.catalog.sync('video')
                return web.json_response(self.video.status())
            if action == 'video/logout':
                self.video.logout()
                with self.b.db:
                    self.b.db.execute("DELETE FROM current_guids WHERE guid LIKE 'fnvideo:%'")
                return web.json_response(self.video.status())
            if action == 'video/libraries':
                ids = (await req.json()).get('ids',[])
                allowed = {r['id'] for r in await self.video.libraries()}
                if not isinstance(ids,list) or any(not isinstance(item, str) for item in ids) or not set(ids) <= allowed:
                    raise SourceError('INVALID_LIBRARY','只能选择当前账号可访问的库')
                self.video.data['libraries'] = ids; self.persist()
                await self.b.catalog.sync('video')
                return web.json_response(self.video.status())
            if action == 'video/sync':
                await self.b.catalog.sync('video')
                return web.json_response(self.video.status())
            if action == 'music/login':
                await self.music.login(await req.json())
                self.b.lyrics_cache.clear()
                with self.b.db:
                    self.b.db.execute("DELETE FROM current_guids WHERE guid NOT LIKE 'video:%' AND guid NOT LIKE 'fnvideo:%' AND guid NOT LIKE 'quark:%'")
                await self.b.catalog.sync('music')
            elif action == 'music/logout':
                self.music.logout()
                with self.b.db:
                    self.b.db.execute("DELETE FROM current_guids WHERE guid NOT LIKE 'video:%' AND guid NOT LIKE 'fnvideo:%' AND guid NOT LIKE 'quark:%'")
            elif action == 'music/sync':
                await self.b.catalog.sync('music')
            else:
                raise web.HTTPNotFound()
            return web.json_response(self.music.status(), headers={'Cache-Control':'no-store'})
        except SourceError as e:
            return web.json_response({'code':e.code,'error':e.message,'retryable':e.retryable}, status=400)
