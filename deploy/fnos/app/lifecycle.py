"""fnOS package lifecycle. No credentials on argv or network installs at runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time
import traceback


ERROR_MESSAGES = {
    'install': '应用安装失败，请检查依赖、目录权限和磁盘空间。',
    'upgrade': '应用升级失败，请检查依赖、目录权限和磁盘空间。',
    'configure': '应用配置或数据迁移失败，请检查迁移目录权限与备份状态。',
    'start': '应用启动失败，请检查端口、应用目录权限和运行环境。',
    'stop': '应用停止失败，请检查应用进程状态和目录权限。',
    'status': '暂时无法确认应用状态，请检查应用进程和目录权限。',
}


def safe_error(action, error):
    action = action if action in ERROR_MESSAGES else 'unknown'
    number = getattr(error, 'errno', None)
    if type(number) is not int or not 0 <= number <= 4095:
        number = None
    frames = [{'file': Path(frame.filename).name, 'line': frame.lineno}
              for frame in traceback.extract_tb(error.__traceback__)
              if Path(frame.filename).name in ('lifecycle.py', 'migrate.py')]
    kind = type(error).__name__ if type(error).__module__ in ('builtins', 'sqlite3') else 'Error'
    return {'version': 1, 'action': action, 'type': kind, 'locations': frames,
            'message': ERROR_MESSAGES.get(action, '应用操作失败，请检查应用状态和目录权限。'),
            'errno': number, 'at': int(time.time())}


def private_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.' + secrets.token_hex(6) + '.tmp')
    try:
        with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as out:
            json.dump(data, out, ensure_ascii=False, indent=2)
            out.flush()
            os.fsync(out.fileno())
        tmp.replace(path)
        os.chmod(path, 0o600)
    finally:
        tmp.unlink(missing_ok=True)


def settings(env, old=None):
    old = dict(old or {})
    target, data = Path(env['TRIM_APPDEST']), Path(env['TRIM_PKGVAR'])
    if not target.is_absolute() or not data.is_absolute():
        raise ValueError('飞牛应用目录必须是绝对路径')
    port = int(env.get('wizard_port') or old.get('port', 8086))
    if not 1024 <= port <= 65535:
        raise ValueError('电视连接端口必须为 1024 至 65535')
    username = (env.get('wizard_username') or old.get('username', 'ktv')).strip()
    if not username or len(username) > 64 or any(ord(c) < 32 for c in username):
        raise ValueError('K歌服务账号格式不正确')
    password = env.get('wizard_password', '')
    if password and not 10 <= len(password) <= 256:
        raise ValueError('K歌服务密码应为 10 至 256 位')
    if password:
        salt = secrets.token_bytes(16)
        old.update(password_salt=salt.hex(), password_hash=hashlib.pbkdf2_hmac(
            'sha256', password.encode(), salt, 200000).hex())
    if password or username != old.get('username') or not old.get('session_key'):
        old['session_key'] = secrets.token_hex(32)
    # No second account is needed for the fnOS gateway. A direct client port is
    # enabled only after a separate service password has explicitly been set.
    old.setdefault('password_salt', secrets.token_hex(16))
    old.setdefault('password_hash', '')
    old.update(username=username, port=port, bind='0.0.0.0', tcp_enabled=bool(old['password_hash']),
               gateway_sso=True,
               base_path='/app/feiniu-karaoke', gateway_socket=str(target/'app.sock'),
               state=str(data/'state.db'), cache_dir=str(data/'audio-cache'),
               score_cache_dir=str(data/'score-cache'), video_cover_cache=str(data/'video-covers'))
    for key, value in dict(lan_networks=[], music_url='', music_token='', video_roots=[],
                           score_generation_enabled=False, score_prewarm=False,
                           score_worker_threads=1, pitch_python=str(data/'pitch-venv/bin/python')).items():
        old.setdefault(key, value)
    if env.get('wizard_video_root', '').strip():
        media = Path(env['wizard_video_root'].strip())
        if not media.is_absolute() or not media.is_dir() or media.resolve() == Path('/') or not os.access(media, os.R_OK):
            raise ValueError('请先在应用设置中授权一个可读取的视频目录，再填写绝对路径')
        old['video_roots'] = [str(media.resolve())]
    return old


class Lifecycle:
    def __init__(self, env=None):
        self.env = dict(os.environ if env is None else env)
        self.target = Path(self.env['TRIM_APPDEST']).resolve()
        self.etc = Path(self.env['TRIM_PKGETC']).resolve()
        self.data = Path(self.env['TRIM_PKGVAR']).resolve()
        self.config = self.etc/'config.json'
        self.pidfile = self.data/'process.json'
        self.server = self.target/'server/server.py'
        self.python = self.target/'runtime/bin/python'

    def record_error(self, action, error):
        private_json(self.data/'last-error.json', safe_error(action, error))

    def clear_error(self, action):
        # fnOS can restart after a failed CONFIG callback. A successful START or
        # routine STATUS must not erase evidence of that failed configuration.
        path = self.data/'last-error.json'
        try:
            previous = json.loads(path.read_text(encoding='utf-8'))
            if isinstance(previous, dict) and previous.get('action') == action:
                path.unlink(missing_ok=True)
        except (OSError, ValueError):
            pass

    def configure(self, initial=False):
        old = json.loads(self.config.read_text(encoding='utf-8')) if self.config.exists() else {}
        # Repeated install callbacks preserve credentials and data, even if wizard
        # values are replayed by the host. Only CONFIG explicitly changes settings.
        env = self.env
        if initial and old:
            env = {k: v for k, v in env.items() if not k.startswith('wizard_')}
        cfg = settings(env, old)
        self.etc.mkdir(parents=True, exist_ok=True)
        self.data.mkdir(parents=True, exist_ok=True)
        os.chmod(self.etc, 0o700)
        os.chmod(self.data, 0o700)
        if old and cfg != old:
            backup = self.etc/'backups'/('config-' + str(time.time_ns()) + '.json')
            private_json(backup, old)
        if not initial and env.get('wizard_import_dir', '').strip():
            from migrate import import_data
            self.stop()
            cfg = import_data(Path(env['wizard_import_dir'].strip()), self.etc, self.data, cfg)
        private_json(self.config, cfg)

    def dependencies(self):
        wheelhouse = self.target/'wheels'
        manifest = json.loads((wheelhouse/'SHA256.json').read_text(encoding='utf-8'))
        if not manifest:
            raise ValueError('安装包缺少基础依赖')
        for name, digest in manifest.items():
            if Path(name).name != name or not name.endswith('.whl'):
                raise ValueError('依赖文件清单无效')
            if hashlib.sha256((wheelhouse/name).read_bytes()).hexdigest() != digest:
                raise ValueError('安装包依赖校验失败，请重新下载')
        result = subprocess.run([sys.executable, '-m', 'venv', str(self.target/'runtime')],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise ValueError('无法创建 Python 3.12 运行环境')
        result = subprocess.run([str(self.python), '-m', 'pip', 'install', '--no-index',
            '--disable-pip-version-check', '--find-links', str(wheelhouse),
            '-r', str(self.target/'server/requirements.txt')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise ValueError('基础依赖离线安装失败，请检查 Python 3.12 与安装包架构')

    @staticmethod
    def identity(pid):
        # /proc start time makes an old PID file harmless after PID reuse.
        text = (Path('/proc')/str(pid)/'stat').read_text()
        return text.rsplit(')', 1)[1].split()[19]

    def process(self):
        try:
            info = json.loads(self.pidfile.read_text())
            pid = info['pid']
            if type(pid) is not int or pid < 2 or self.identity(pid) != info['start']:
                return None
            cmd = (Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0')
            if str(self.server).encode() not in cmd:
                return None
            os.kill(pid, 0)
            return pid
        except (OSError, ValueError, KeyError, TypeError, IndexError):
            return None

    def healthy(self):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                client.settimeout(1)
                client.connect(str(self.target/'app.sock'))
                client.sendall(b'GET /app/feiniu-karaoke/api/tv-auth/session HTTP/1.0\r\nHost: localhost\r\n\r\n')
                return client.recv(256).startswith(b'HTTP/1.0 200')
        except OSError:
            return False

    def start(self):
        if self.process():
            if self.healthy():
                return
            raise ValueError('应用进程存在但尚未就绪，请停止后重试')
        if not self.python.is_file() or not self.config.is_file():
            raise ValueError('应用安装未完成，请重新安装应用包')
        for name in ('ffmpeg', 'ffprobe'):
            if not Path('/usr/bin', name).is_file():
                raise ValueError('系统缺少 FFmpeg，暂不能启动播放服务')
        env = {k: v for k, v in self.env.items() if not k.startswith('wizard_')}
        env.update(MAIDONG_CONFIG=str(self.config), PYTHONUNBUFFERED='1')
        log = self.data/'service.log'
        if log.exists() and log.stat().st_size > 5*1024*1024:
            log.replace(self.data/'service.previous.log')
        with log.open('ab') as output:
            proc = subprocess.Popen([str(self.python), str(self.server)], cwd=self.data,
                env=env, stdout=output, stderr=output, stdin=subprocess.DEVNULL, start_new_session=True)
        private_json(self.pidfile, {'pid': proc.pid, 'start': self.identity(proc.pid)})
        for _ in range(60):
            if proc.poll() is not None:
                self.pidfile.unlink(missing_ok=True)
                raise ValueError('应用启动失败，请检查端口是否被占用及应用日志')
            if self.healthy():
                return
            time.sleep(0.25)
        self.stop()
        raise ValueError('应用启动超时，请检查应用日志')

    def stop(self):
        pid = self.process()
        if pid:
            os.kill(pid, signal.SIGTERM)
            for _ in range(60):
                if not self.process():
                    break
                time.sleep(0.25)
            if self.process() == pid:
                os.kill(pid, signal.SIGKILL)
        self.pidfile.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('install', 'upgrade', 'configure', 'start', 'stop', 'status'))
    action = parser.parse_args().action
    os.umask(0o077)
    app = None
    try:
        app = Lifecycle()
        if action in ('install', 'upgrade'):
            app.configure(initial=True)
            app.dependencies()
        elif action == 'configure':
            app.configure()
        elif action == 'status':
            if not (app.process() and app.healthy()):
                return 3
        else:
            getattr(app, action)()
        app.clear_error(action)
        return 0
    except Exception as error:
        # Never print exception values from subprocesses, config files or host env.
        message = safe_error(action, error)['message']
        if app is not None:
            try:
                app.record_error(action, error)
            except OSError:
                pass  # The app data path itself may be the denied/full path.
        path = os.environ.get('TRIM_TEMP_LOGFILE')
        if path:
            try:
                Path(path).write_text(message, encoding='utf-8')
            except OSError:
                pass
        print(message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
