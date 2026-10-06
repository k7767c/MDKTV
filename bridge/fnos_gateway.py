"""fnOS gateway paths and listeners with kernel-verified NAS service peers.

The NAS gateway keeps /app/<application> in the upstream URL.  Both that URL
and the original TCP API share one Bridge and therefore one playback queue.
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import struct
import secrets
import time
from functools import lru_cache
from urllib.parse import urlsplit

from aiohttp import web


def base_path(config):
    value = config.get('base_path', '')
    if not isinstance(value, str) or (value and not re.fullmatch(r'/app/[a-zA-Z0-9_-]+', value)):
        raise ValueError('base_path must be empty or /app/<application>')
    if config.get('gateway_socket') and not value:
        raise ValueError('gateway_socket requires base_path')
    return value


def unix_request(req):
    """The transport, never X-Trim/X-Forwarded headers, identifies the gateway."""
    transport = req.transport
    sock = transport.get_extra_info('socket') if transport else None
    return bool(sock is not None and sock.family == getattr(socket, 'AF_UNIX', None))


@lru_cache(maxsize=1)
def gateway_peer_uids():
    """Only the verified fnOS nginx/root service accounts may assert NAS identity."""
    allowed = {0}
    try:
        import pwd
        allowed.add(pwd.getpwnam('www-data').pw_uid)
    except (ImportError, KeyError):
        pass
    return frozenset(allowed)


def trusted_gateway_peer(req):
    if not unix_request(req) or not hasattr(socket, 'SO_PEERCRED'):
        return False
    try:
        sock = req.transport.get_extra_info('socket')
        credentials = sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i'))
        _pid, uid, _gid = struct.unpack('3i', credentials)
        return uid in gateway_peer_uids()
    except (OSError, ValueError, TypeError, struct.error, AttributeError):
        return False


def gateway_middleware(config):
    prefix = base_path(config)

    @web.middleware
    async def middleware(req, handler):
        gateway = bool(config.get('gateway_socket')) and unix_request(req)
        trusted_peer = gateway and trusted_gateway_peer(req)
        identity = None
        user_id = req.headers.get('X-Trim-Userid', '')
        if (config.get('gateway_sso', False) and trusted_peer and re.fullmatch(r'[0-9]{1,20}', user_id)
                and len(req.headers.getall('X-Trim-Userid', [])) == 1
                and len(req.headers.getall('X-Trim-Isadmin', [])) <= 1):
            identity = {'uid': user_id, 'admin': req.headers.get('X-Trim-Isadmin', '').lower() in ('true', '1')}
        request_prefix = ''
        overrides = {}
        if prefix and (req.path == prefix or req.path.startswith(prefix + '/')):
            # Preserve percent encoding and query values while existing handlers
            # continue to see their original paths and matched route parameters.
            raw_path = req.rel_url.raw_path
            if raw_path != prefix and not raw_path.startswith(prefix + '/'):
                raise web.HTTPBadRequest(text='Invalid application path')
            overrides['rel_url'] = req.rel_url.with_path(raw_path[len(prefix):] or '/', encoded=True,
                                                       keep_query=True)
            request_prefix = prefix
        if trusted_peer:
            # fnOS may preserve Host or provide the external authority separately.
            # On TCP these headers have no effect on origin/cookie decisions.
            forwarded_host = req.headers.get('X-Forwarded-Host', '')
            if forwarded_host and ',' not in forwarded_host and '/' not in forwarded_host:
                overrides['host'] = forwarded_host
            if req.headers.get('X-Forwarded-Proto', '').lower() == 'https':
                overrides['scheme'] = 'https'
        if overrides:
            req = req.clone(**overrides)
        req['app_base_path'] = request_prefix
        req['fnos_gateway'] = gateway
        req['fnos_identity'] = identity
        return await handler(req)

    return middleware


PROOF_PROTOCOL = 'fnos-proof.'
PROOF_TTL = 120


def _gateway_origin(req, origin, missing_port_only=False):
    """Validate a canonical browser origin against the kernel-trusted gateway host."""
    identity = req.get('fnos_identity')
    if not req.get('fnos_gateway') or not identity or not identity.get('uid') or len(origin) > 1024:
        return False
    try:
        parsed = urlsplit(origin)
        host = urlsplit('http://' + req.host)
        if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
                or parsed.hostname != host.hostname):
            return False
        # Validate numeric ports even when the gateway omitted its own.
        parsed.port
        if missing_port_only:
            return host.port is None
        return host.port is None or parsed.netloc == req.host
    except (ValueError, TypeError):
        return False


def issue_gateway_proof(req, config):
    """Short same-origin proof; never a NAS credential or an application login."""
    if len(req.headers.getall('X-Karaoke-Origin', [])) != 1:
        return {}
    origin = req.headers.get('X-Karaoke-Origin', '')
    if not _gateway_origin(req, origin):
        return {}
    expiry = int(time.time()) + PROOF_TTL
    payload = ['gateway-origin-v1', req['fnos_identity']['uid'], origin, expiry, secrets.token_hex(12)]
    encoded = base64.urlsafe_b64encode(json.dumps(payload, separators=(',', ':')).encode()).rstrip(b'=')
    signature = hmac.new(bytes.fromhex(config['session_key']), encoded, hashlib.sha256).hexdigest()
    return {'gatewayProof': encoded.decode() + '.' + signature, 'gatewayProofExpiresAt': expiry}


def valid_gateway_origin_proof(req, config):
    """Allow only a proven browser origin when fnOS stripped Host's port.

    Session responses use no-store and no CORS. A foreign origin cannot read
    this proof. It is sent in a private header/subprotocol, never a URL/log.
    TCP, unauthenticated Unix peers and arbitrary same-host ports remain denied.
    """
    origin = req.headers.get('Origin', '')
    if len(req.headers.getall('Origin', [])) != 1 or not _gateway_origin(req, origin, missing_port_only=True):
        return False
    if req.path == '/ws':
        protocols = [value.strip() for value in req.headers.get('Sec-WebSocket-Protocol', '').split(',')]
        values = [value[len(PROOF_PROTOCOL):] for value in protocols if value.startswith(PROOF_PROTOCOL)]
    elif req.path.startswith('/api/'):
        values = req.headers.getall('X-Karaoke-Proof', [])
    else:
        return False
    if len(values) != 1 or len(values[0]) > 2048:
        return False
    try:
        encoded, signature = values[0].rsplit('.', 1)
        expected = hmac.new(bytes.fromhex(config['session_key']), encoded.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            return False
        value = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        now = time.time()
        return (isinstance(value, list) and len(value) == 5 and value[0] == 'gateway-origin-v1'
                and value[1] == req['fnos_identity']['uid'] and value[2] == origin
                and type(value[3]) is int and now < value[3] <= now + PROOF_TTL)
    except (ValueError, TypeError, UnicodeError, KeyError):
        return False


def scoped_url(req, value):
    """Only rewrite application-owned paths; never rewrite an upstream URL."""
    prefix = req.get('app_base_path', '')
    if prefix and isinstance(value, str) and value.startswith('/') and not value.startswith('//'):
        if value != prefix and not value.startswith(prefix + '/'):
            return prefix + value
    return value


class AppListeners:
    """Own the gateway socket and optional legacy TCP listener together."""
    def __init__(self, app, config):
        self.app, self.config = app, config
        self.runner = web.AppRunner(app, access_log=None)
        self.socket_path = None
        self.socket_identity = None
        self.sites = []

    async def start(self):
        config = self.config
        base_path(config)
        gateway_path = config.get('gateway_socket')
        tcp_enabled = config.get('tcp_enabled', True)
        if not gateway_path and not tcp_enabled:
            raise ValueError('At least one application listener must be enabled')
        await self.runner.setup()
        try:
            if gateway_path:
                path = Path(gateway_path)
                if not path.is_absolute():
                    raise ValueError('gateway_socket must be an absolute path')
                path.parent.mkdir(parents=True, exist_ok=True)
                if path.exists() or path.is_symlink():
                    # Never overwrite a file, symlink or another running service.
                    info = path.lstat()
                    if not stat.S_ISSOCK(info.st_mode):
                        raise RuntimeError('Gateway path already exists and is not a socket')
                    probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    probe.settimeout(1)
                    try:
                        probe.connect(str(path))
                    except ConnectionRefusedError:
                        # A killed prior instance may leave its inactive socket.
                        # Recheck the inode before removing only that socket.
                        current = path.lstat()
                        if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
                            raise RuntimeError('Gateway socket changed during startup')
                        path.unlink()
                    else:
                        raise RuntimeError('Gateway socket is already in use')
                    finally:
                        probe.close()
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                try:
                    sock.bind(str(path))
                    self.socket_path = path
                    info = path.lstat()
                    self.socket_identity = (info.st_dev, info.st_ino)
                    # The NAS gateway runs as a different service account. The
                    # SSO path also requires its kernel-verified peer identity.
                    os.chmod(path, 0o666)
                    site = web.SockSite(self.runner, sock)
                    await site.start()
                    self.sites.append(site)
                except BaseException:
                    sock.close()
                    raise
            if tcp_enabled:
                site = web.TCPSite(self.runner, host=config.get('bind', '0.0.0.0'),
                                   port=int(config.get('port', 8084)))
                await site.start()
                self.sites.append(site)
        except BaseException:
            await self.close()
            raise
        return self

    async def close(self):
        try:
            await self.runner.cleanup()
        finally:
            if self.socket_path is not None:
                try:
                    info = self.socket_path.lstat()
                    if stat.S_ISSOCK(info.st_mode) and (info.st_dev, info.st_ino) == self.socket_identity:
                        self.socket_path.unlink()
                except FileNotFoundError:
                    pass
                self.socket_path = None


async def serve(app, config):
    listeners = await AppListeners(app, config).start()
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    installed = []
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stopped.set)
                installed.append(sig)
            except NotImplementedError:
                pass  # Windows Ctrl-C cancels asyncio.run; finally still cleans up.
        await stopped.wait()
    finally:
        for sig in installed:
            loop.remove_signal_handler(sig)
        await listeners.close()
