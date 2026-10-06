"""Read-only Quark mounts. Provider credentials stay in the encrypted NAS vault."""
import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlencode, urljoin, urlsplit
import uuid

from aiohttp import ClientError, ClientSession, ClientTimeout, CookieJar, DummyCookieJar, web
from source_auth import SourceError


PAN = 'https://pan.quark.cn'
DRIVE = 'https://drive-pc.quark.cn'
UOP = 'https://uop.quark.cn/cas/ajax/'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36'
DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 Electron/18.3.5.12'
AUDIO = frozenset(('.mp3', '.flac', '.wav', '.m4a', '.aac', '.ogg', '.opus', '.wma', '.ape', '.aiff'))
VIDEO = frozenset(('.mp4', '.mkv', '.ts', '.avi', '.mov', '.webm', '.m4v', '.mpg', '.mpeg', '.vob', '.m2ts', '.mts', '.flv', '.wmv'))


def file_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
        raise SourceError('INVALID_FILE', '夸克文件编号无效')
    return value


def safe_media_url(value):
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or '').lower()
        return (parsed.scheme == 'https' and not parsed.username and not parsed.password
                and parsed.port in (None, 443) and any(host == domain or host.endswith('.' + domain)
                    for domain in ('quark.cn', 'quark.com', 'aliyuncs.com')))
    except (TypeError, ValueError):
        return False


def valid_cookie(name, value):
    return (isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_]{1,100}', name)
            and isinstance(value, str) and len(value) <= 8192 and not any(ord(c) < 32 or c == ';' for c in value))


def object_value(value):
    return value if isinstance(value, dict) else {}


class QuarkSource:
    kind = 'quark'

    def __init__(self, owner, data):
        self.owner, self.data = owner, dict(data)
        self.generation = 0
        self.state = 'unverified' if self.authenticated else 'unconfigured'
        self.error = ''
        self.attempts = {}
        self.links = {}
        self.scan_task = None
        self.progress = {'state': 'idle', 'checkedFolders': 0, 'songs': 0, 'files': 0}
        # A non-remembered account disappears on process restart. Never leave
        # its metadata visible while no matching encrypted account is loaded.
        with owner.b.db:
            owner.b.db.execute("DELETE FROM current_guids WHERE guid LIKE 'quark:%' AND guid NOT LIKE ?",
                ('quark:' + str(self.data.get('scope') or '') + ':%' if self.authenticated else '',))

    @property
    def authenticated(self):
        return bool(self.data.get('scope') and self.data.get('cookies'))

    def status(self):
        return {'id': 'quark', 'state': self.state, 'error': self.error,
            'connected': self.authenticated, 'remember': self.data.get('remember', True),
            'mounts': self.data.get('mounts', []), 'scan': dict(self.progress),
            'loginMethod': 'official_qr', 'nativePhoneLoginAvailable': False}

    def principal(self, req):
        identity = req.get('fnos_identity') or {}
        value = identity.get('uid') or req.cookies.get('maidong_session', '')
        return hashlib.sha256(str(value).encode()).hexdigest()

    @asynccontextmanager
    async def session(self, supplied=None):
        if supplied is not None:
            yield supplied
        else:
            async with ClientSession(cookie_jar=DummyCookieJar(), trust_env=False) as session:
                yield session

    async def json_request(self, url, params=None, body=None, *, session=None, desktop=False):
        generation = self.generation
        headers = {'User-Agent': DESKTOP_UA if desktop else UA, 'Referer': PAN + '/',
                   'Origin': PAN, 'Accept': 'application/json'}
        if session is None:
            headers['Cookie'] = '; '.join(name + '=' + value for name, value in self.data.get('cookies', {}).items()
                if valid_cookie(name, value))
        try:
            async with self.session(session) as client:
                async with client.request('POST' if body is not None else 'GET', url, params=params, json=body,
                        headers=headers, allow_redirects=False, timeout=ClientTimeout(total=25, connect=6)) as response:
                    if response.status != 200:
                        raise SourceError('AUTH_REQUIRED' if response.status == 401 else 'SOURCE_OFFLINE',
                            '夸克登录已过期，请重新扫码' if response.status == 401 else '夸克接口暂不可用', True)
                    payload = bytearray()
                    async for chunk in response.content.iter_chunked(65536):
                        payload.extend(chunk)
                        if len(payload) > 4 * 1024 * 1024:
                            raise SourceError('INVALID_RESPONSE', '夸克返回内容过大')
                    result = json.loads(payload)
                    fresh = {name: cookie.value for name, cookie in response.cookies.items()
                        if valid_cookie(name, cookie.value) and (not cookie['domain'] or
                            cookie['domain'].lstrip('.').lower() == 'quark.cn' or
                            cookie['domain'].lstrip('.').lower().endswith('.quark.cn'))}
            if not isinstance(result, dict):
                raise SourceError('INVALID_RESPONSE', '夸克返回内容异常')
            if session is None:
                if generation != self.generation:
                    raise SourceError('STALE_SESSION', '夸克账号已变更')
                if fresh:
                    self.data.setdefault('cookies', {}).update(fresh)
                    self.owner.persist()
            return result
        except (ClientError, asyncio.TimeoutError, ValueError):
            raise SourceError('SOURCE_OFFLINE', '夸克连接失败或响应异常，请稍后重试', True) from None

    async def api_request(self, path, *, body=None, params=None, desktop=False):
        if not self.authenticated:
            raise SourceError('AUTH_REQUIRED', '请先扫码登录夸克网盘')
        query = {'pr': 'ucpro', 'fr': 'pc', 'uc_param_str': '', **(params or {})}
        if desktop:
            query.update(sys='win32', ve='2.5.56', ut='', guid='')
        data = await self.json_request(DRIVE + '/1/clouddrive/' + path, query, body, desktop=desktop)
        if data.get('status') != 200:
            code = data.get('code')
            if code in (31001, 41001) or data.get('status') == 401:
                self.state = 'reauth_required'
                raise SourceError('AUTH_REQUIRED', '夸克登录已过期，请重新扫码')
            if code == 23018:
                raise SourceError('DOWNLOAD_RESTRICTED', '夸克限制此文件的下载')
            raise SourceError('UPSTREAM_ERROR', '夸克接口暂不可用，请稍后重试', True)
        self.state = 'authenticated'
        return data

    async def begin(self, principal, remember=True):
        for attempt_id, attempt in list(self.attempts.items()):
            if attempt['principal'] == principal or time.monotonic() >= attempt['expires']:
                await self.cancel_attempt(attempt_id)
        if len(self.attempts) >= 4:
            raise SourceError('LOGIN_BUSY', '登录待办较多，请稍后重试')
        session = ClientSession(cookie_jar=CookieJar(), trust_env=False)
        try:
            data = await self.json_request(UOP + 'getTokenForQrcodeLogin',
                {'client_id': '532', 'v': '1.2', 'request_id': str(uuid.uuid4())}, session=session)
            token = object_value(object_value(data.get('data')).get('members')).get('token')
            if data.get('status') != 2000000 or not isinstance(token, str) or not token or len(token) > 4096:
                raise SourceError('LOGIN_UNAVAILABLE', '夸克未返回有效二维码，请刷新重试')
            attempt_id = secrets.token_urlsafe(24)
            self.attempts[attempt_id] = {'session': session, 'token': token, 'expires': time.monotonic() + 180,
                'generation': self.generation, 'principal': principal, 'remember': bool(remember),
                'lock': asyncio.Lock(), 'lastPoll': 0, 'state': 'waiting'}
            # The URL is returned only to the authorized QR page, never stored in
            # catalog metadata, log messages, status endpoints or player URLs.
            uri = 'https://su.quark.cn/4_eMHBJ?' + urlencode({'token': token, 'client_id': '532', 'ssb': 'weblogin',
                'uc_param_str': '', 'uc_biz_str': 'S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0'})
            return {'attemptId': attempt_id, 'qrLoginUrl': uri, 'expiresIn': 180, 'state': 'waiting'}
        except BaseException:
            await session.close()
            raise

    async def cancel_attempt(self, attempt_id):
        attempt = self.attempts.pop(attempt_id, None)
        if attempt:
            attempt['state'] = 'cancelled'
            await attempt['session'].close()

    async def poll(self, attempt_id, principal):
        attempt = self.attempts.get(attempt_id)
        if not attempt or attempt['principal'] != principal:
            raise SourceError('INVALID_ATTEMPT', '二维码已失效，请重新生成')
        if time.monotonic() >= attempt['expires'] or attempt['generation'] != self.generation:
            await self.cancel_attempt(attempt_id)
            return {'state': 'expired'}
        async with attempt['lock']:
            if self.attempts.get(attempt_id) is not attempt or time.monotonic() >= attempt['expires'] or attempt['generation'] != self.generation:
                await self.cancel_attempt(attempt_id)
                return {'state': 'expired'}
            if time.monotonic() - attempt['lastPoll'] < 1.5:
                return {'state': attempt['state']}
            attempt['lastPoll'] = time.monotonic()
            if not attempt.get('ticket'):
                data = await self.json_request(UOP + 'getServiceTicketByQrcodeToken',
                    {'client_id': '532', 'v': '1.2', 'request_id': str(uuid.uuid4()), 'token': attempt['token']},
                    session=attempt['session'])
                ticket = object_value(object_value(data.get('data')).get('members')).get('service_ticket')
                if data.get('status') != 2000000 or not isinstance(ticket, str) or not ticket:
                    return {'state': 'waiting'}
                attempt['ticket'] = ticket
                attempt['state'] = 'verifying'
            info = await self.json_request(PAN + '/account/info', {'st': attempt['ticket'], 'lw': 'scan'}, session=attempt['session'])
            probe = await self.json_request(DRIVE + '/1/clouddrive/file/sort',
                {'pr': 'ucpro', 'fr': 'pc', 'uc_param_str': '', 'pdir_fid': '0', '_page': 1, '_size': 1},
                session=attempt['session'])
            cookies = {cookie.key: cookie.value for cookie in attempt['session'].cookie_jar
                if valid_cookie(cookie.key, cookie.value) and (cookie['domain'].lstrip('.') == 'quark.cn' or
                    cookie['domain'].lstrip('.').endswith('.quark.cn'))}
            if probe.get('status') != 200 or not isinstance(object_value(probe.get('data')).get('list'), list) or not cookies:
                raise SourceError('LOGIN_REJECTED', '扫码已确认，但夸克未授权读取网盘；请刷新二维码重试')
            user = info.get('data') or {}
            uid = user.get('user_id') or user.get('uid') if isinstance(user, dict) else None
            scope = hashlib.sha256(str(uid).encode()).hexdigest()[:24] if uid else 'session-' + uuid.uuid4().hex
            if self.attempts.get(attempt_id) is not attempt or attempt['generation'] != self.generation:
                return {'state': 'expired'}
            old_scope = self.data.get('scope')
            mounts = self.data.get('mounts', []) if scope == old_scope else []
            self.generation += 1
            commit_generation = self.generation
            await self.cancel_scan()
            if self.generation != commit_generation or self.attempts.get(attempt_id) is not attempt:
                return {'state': 'expired'}
            self.data = {'scope': scope, 'cookies': cookies, 'mounts': mounts, 'remember': attempt['remember']}
            self.state, self.error = 'authenticated', ''
            self.links.clear()
            self.owner.persist()
            if old_scope != scope:
                with self.owner.b.db:
                    self.owner.b.db.execute("DELETE FROM current_guids WHERE guid LIKE 'quark:%'")
            await self.cancel_attempt(attempt_id)
            return {'state': 'authorized', 'source': self.status()}

    async def list_folder(self, parent='0', page=1):
        parent = file_id(parent)
        if type(page) is not int or not 1 <= page <= 10000:
            raise SourceError('INVALID_PAGE', '目录页码无效')
        data = await self.api_request('file/sort', params={'pdir_fid': parent, '_page': page, '_size': 100,
            '_fetch_total': 1, '_sort': 'file_type:asc,file_name:asc'})
        content = object_value(data.get('data'))
        rows = content.get('list')
        total = object_value(data.get('metadata')).get('_total', content.get('total'))
        if not isinstance(rows, list) or len(rows) > 100 or total is not None and (type(total) is not int or total < 0):
            raise SourceError('INVALID_RESPONSE', '夸克目录响应不完整，保留原曲库')
        for row in rows:
            if not isinstance(row, dict):
                raise SourceError('INVALID_RESPONSE', '夸克目录条目异常')
            file_id(row.get('fid'))
        return rows, total

    async def mount(self, parent, name):
        parent = file_id(parent)
        generation = self.generation
        await self.list_folder(parent, 1)  # Verify access before changing a mount.
        await self.cancel_scan()
        if generation != self.generation:
            raise SourceError('STALE_SESSION', '夸克账号已变更，请重新选择目录')
        self.generation += 1
        self.data['mounts'] = [{'id': parent, 'name': str(name or '音乐目录')[:200]}]
        self.owner.persist()
        return self.status()

    def media_row(self, item, parent):
        name = str(item.get('file_name', ''))
        extension = Path(name).suffix.lower()
        if item.get('dir') or item.get('file_type') == 0 or extension not in AUDIO | VIDEO:
            return None
        stem = Path(name).stem
        parts = re.split(r'\s*[-－_]\s*', stem, maxsplit=1)
        artist, title = parts if len(parts) == 2 and all(parts) else ('未知歌手', stem)
        return {'title': title, 'artist': artist, 'album': '', 'genre': 'MV / 视频' if extension in VIDEO else '音乐',
            'language': '未分类', 'duration': 0, 'format': extension[1:], 'media_kind': 'mv' if extension in VIDEO else 'audio',
            'audio_only': extension in AUDIO, 'provider': 'quark', 'file_name': name,
            'quark_fid': file_id(item['fid']), 'quark_parent': parent,
            'revision': str(item.get('updated_at') or item.get('last_update_at') or item.get('size') or 0)}

    async def scan_rows(self):
        if not self.authenticated:
            return {}
        generation, scope = self.generation, self.data['scope']
        pending = [file_id(mount['id']) for mount in self.data.get('mounts', [])]
        visited, result, lyrics = set(), {}, {}
        self.progress = {'state': 'scanning', 'checkedFolders': 0, 'songs': 0, 'files': 0}
        while pending:
            if generation != self.generation:
                raise SourceError('STALE_SESSION', '夸克扫描已取消或账号变更')
            parent = pending.pop()
            if parent in visited:
                continue
            visited.add(parent)
            seen, expected_total = set(), None
            for page in range(1, 10001):
                items, total = await self.list_folder(parent, page)
                if generation != self.generation:
                    raise SourceError('STALE_SESSION', '夸克账号已变更')
                if total is not None:
                    if expected_total is not None and expected_total != total:
                        raise SourceError('INVALID_RESPONSE', '扫描期间夸克目录发生变化，保留原曲库，请重新扫描')
                    expected_total = total
                fresh = 0
                for item in items:
                    ident = file_id(item['fid'])
                    if ident in seen:
                        continue
                    seen.add(ident)
                    fresh += 1
                    self.progress['files'] += 1
                    if item.get('dir') or item.get('file_type') == 0:
                        pending.append(ident)
                    else:
                        row = self.media_row(item, parent)
                        if row:
                            result['quark:' + scope + ':' + ident] = row
                        if Path(str(item.get('file_name', ''))).suffix.lower() == '.lrc':
                            lyrics[(parent, Path(item['file_name']).stem.casefold())] = ident
                self.progress.update(checkedFolders=len(visited), songs=len(result))
                if len(visited) > 5000 or len(result) > 100000 or self.progress['files'] > 500000:
                    raise SourceError('SCAN_LIMIT', '目录过大，请选择较小的音乐目录')
                if items and fresh == 0:
                    raise SourceError('INVALID_RESPONSE', '夸克分页重复，保留原曲库')
                if total is not None and len(seen) > total:
                    raise SourceError('INVALID_RESPONSE', '夸克目录条目数异常，保留原曲库')
                if total is not None and len(seen) == total:
                    break
                if not items or len(items) < 100:
                    if total is not None and len(seen) < total:
                        raise SourceError('INVALID_RESPONSE', '夸克目录读取不完整，保留原曲库')
                    break
                if page == 10000:
                    raise SourceError('SCAN_LIMIT', '夸克目录分页过多，请选择较小目录')
                await asyncio.sleep(0)
        for row in result.values():
            lyric = lyrics.get((row['quark_parent'], Path(row['file_name']).stem.casefold()))
            if lyric:
                row['quark_lyric'] = lyric
        self.progress.update(state='ready', songs=len(result))
        return result

    async def start_scan(self):
        if not self.authenticated:
            raise SourceError('AUTH_REQUIRED', '请先扫码登录夸克网盘')
        if not self.data.get('mounts'):
            raise SourceError('MOUNT_REQUIRED', '请先选择要挂载的音乐目录')
        if self.scan_task and not self.scan_task.done():
            return self.status()
        self.error = ''
        self.progress = {'state': 'scanning', 'checkedFolders': 0, 'songs': 0, 'files': 0}
        async def run():
            try:
                await self.owner.b.catalog.sync('quark')
                if self.owner.b.catalog.status['quark']['state'] == 'offline':
                    self.progress.update(state='error')
                    self.error = '目录扫描失败，保留上次完整曲库；可以重试。'
            except asyncio.CancelledError:
                self.progress.update(state='cancelled')
                raise
        self.scan_task = asyncio.create_task(run())
        return self.status()

    async def cancel_scan(self):
        if self.scan_task and not self.scan_task.done():
            self.scan_task.cancel()
            await asyncio.gather(self.scan_task, return_exceptions=True)
        self.scan_task = None

    def resolve(self, guid):
        parts = guid.split(':', 2)
        if len(parts) != 3 or parts[0] != 'quark' or not self.authenticated or parts[1] != self.data.get('scope'):
            raise SourceError('STALE_SESSION', '夸克账号已退出或变更，请重新选择曲目')
        return file_id(parts[2])

    async def link(self, ident, refresh=False):
        ident = file_id(ident)
        generation = self.generation
        cached = self.links.get(ident)
        if cached and cached[0] > time.monotonic() and not refresh:
            return cached[1]
        try:
            data = await self.api_request('file/download', body={'fids': [ident]})
        except SourceError as error:
            if error.code != 'DOWNLOAD_RESTRICTED':
                raise
            data = await self.api_request('file/download', body={'fids': [ident]}, desktop=True)
        entries = data.get('data')
        url = entries[0].get('download_url') if isinstance(entries, list) and entries and isinstance(entries[0], dict) else None
        if not safe_media_url(url):
            raise SourceError('INVALID_MEDIA', '夸克未返回安全的播放地址')
        if generation != self.generation:
            raise SourceError('STALE_SESSION', '夸克账号已变更')
        if len(self.links) >= 256:
            self.links.pop(next(iter(self.links)))
        self.links[ident] = (time.monotonic() + 60, url)
        return url

    @asynccontextmanager
    async def media_response(self, guid, requested=None):
        ident = self.resolve(guid)
        generation = self.generation
        if requested and not re.fullmatch(r'bytes=(?:\d+-\d*|-\d+)', requested):
            raise web.HTTPRequestRangeNotSatisfiable()
        try:
            async with ClientSession(cookie_jar=DummyCookieJar(), trust_env=False) as session:
                for refresh in (False, True):
                    url = await self.link(ident, refresh)
                    for hop in range(4):
                        if generation != self.generation:
                            raise SourceError('STALE_SESSION', '夸克账号已变更')
                        if not safe_media_url(url):
                            raise SourceError('INVALID_MEDIA', '夸克播放地址不安全')
                        host = urlsplit(url).hostname.lower()
                        headers = {'User-Agent': UA, 'Referer': PAN + '/', 'Accept-Encoding': 'identity'}
                        if host == 'quark.cn' or host.endswith('.quark.cn') or host == 'quark.com' or host.endswith('.quark.com'):
                            headers['Cookie'] = '; '.join(k + '=' + v for k, v in self.data.get('cookies', {}).items()
                                if k in ('__pus', '__puus') and valid_cookie(k, v))
                        if requested:
                            headers['Range'] = requested
                        response = await session.get(url, headers=headers, allow_redirects=False,
                            timeout=ClientTimeout(total=None, connect=6, sock_read=25))
                        if response.status in (301, 302, 303, 307, 308):
                            url = urljoin(url, response.headers.get('Location', ''))
                            response.release()
                            continue
                        break
                    else:
                        raise SourceError('INVALID_MEDIA', '夸克播放地址重定向过多')
                    if response.status in (401, 403) and not refresh:
                        response.release()
                        continue
                    if generation != self.generation:
                        response.release()
                        raise SourceError('STALE_SESSION', '夸克账号已变更')
                    try:
                        yield response
                    finally:
                        response.release()
                    return
        except (ClientError, asyncio.TimeoutError):
            raise SourceError('SOURCE_OFFLINE', '夸克媒体读取失败，请稍后重试', True) from None
        raise SourceError('INVALID_MEDIA', '夸克播放地址已失效，请重试')

    async def lyrics(self, guid, ident):
        self.resolve(guid)
        lyric_guid = 'quark:' + self.data['scope'] + ':' + file_id(ident)
        generation = self.generation
        async with self.media_response(lyric_guid) as response:
            if response.status != 200:
                return ''
            data = bytearray()
            async for chunk in response.content.iter_chunked(65536):
                if generation != self.generation:
                    raise SourceError('STALE_SESSION', '夸克账号已变更')
                data.extend(chunk)
                if len(data) > 1024 * 1024:
                    raise SourceError('INVALID_LYRICS', '歌词文件过大')
        for encoding in ('utf-8-sig', 'gb18030', 'utf-16'):
            try:
                return bytes(data).decode(encoding)
            except UnicodeError:
                pass
        return ''

    async def logout(self):
        self.generation += 1
        await self.cancel_scan()
        for attempt_id in list(self.attempts):
            await self.cancel_attempt(attempt_id)
        self.links.clear()
        self.data = {'remember': True}
        self.state, self.error = 'unconfigured', ''
        self.progress = {'state': 'idle', 'checkedFolders': 0, 'songs': 0, 'files': 0}
        self.owner.persist()
        with self.owner.b.db:
            self.owner.b.db.execute("DELETE FROM current_guids WHERE guid LIKE 'quark:%'")

    async def close(self):
        await self.cancel_scan()
        for attempt_id in list(self.attempts):
            await self.cancel_attempt(attempt_id)

    async def api(self, req, action):
        if not self.owner.b.is_admin_session(req):
            raise web.HTTPUnauthorized(text='夸克账号和目录挂载需要飞牛管理员或应用管理账号')
        allowed = ('status', 'folders')
        if req.method not in (['GET'] if action in allowed else ['POST']):
            raise web.HTTPMethodNotAllowed(req.method, ['GET'] if action in allowed else ['POST'])
        try:
            body = await req.json() if req.method == 'POST' and req.can_read_body else {}
            if not isinstance(body, dict):
                raise SourceError('INVALID_REQUEST', '请求参数无效')
            principal = self.principal(req)
            if action == 'status':
                result = self.status()
            elif action == 'begin':
                result = await self.begin(principal, body.get('remember', True))
            elif action == 'poll':
                result = await self.poll(body.get('attemptId', ''), principal)
            elif action == 'cancel':
                attempt_id = body.get('attemptId', '')
                attempt = self.attempts.get(attempt_id)
                if attempt and attempt['principal'] != principal:
                    raise SourceError('INVALID_ATTEMPT', '二维码操作不属于当前用户')
                await self.cancel_attempt(attempt_id)
                result = {'state': 'cancelled'}
            elif action == 'folders':
                parent, page = req.query.get('parent', '0'), int(req.query.get('page', 1))
                rows, total = await self.list_folder(parent, page)
                result = {'items': [{'id': row['fid'], 'name': str(row.get('file_name', '未命名')),
                    'directory': bool(row.get('dir') or row.get('file_type') == 0)} for row in rows],
                    'total': total, 'page': page, 'pageSize': 100, 'parent': parent, 'more':
                    page * 100 < total if total is not None else len(rows) == 100}
            elif action == 'mount':
                result = await self.mount(body.get('parent', ''), body.get('name', '音乐目录'))
            elif action == 'scan':
                result = await self.start_scan()
            elif action == 'scan-cancel':
                await self.cancel_scan()
                result = self.status()
            elif action == 'logout':
                await self.logout()
                result = self.status()
            else:
                raise web.HTTPNotFound()
            return web.json_response(result, headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer'})
        except SourceError as error:
            return web.json_response({'code': error.code, 'error': error.message, 'retryable': error.retryable}, status=400)
