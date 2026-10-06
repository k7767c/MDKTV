"""Explicit, private migration snapshots; never run against a live destination."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import time
import uuid


CONFIG_KEYS = frozenset(('music_url', 'music_token', 'username', 'password_salt', 'password_hash', 'video_roots'))
SOURCE_CONFIG_KEYS = frozenset(('music_url', 'music_token', 'video_roots'))
CACHE_FILES = ('melody-v2.json', 'accompaniment.m4a', 'online-match.json')
VAULT_FILES = ('source-secrets/keys/sources.key', 'source-secrets/sources.enc')
FORMAT = 'feiniu-karaoke-migration'


def safe_path(path, root=None):
    path = Path(path).absolute()
    if '..' in path.parts or (root is not None and not path.is_relative_to(Path(root).absolute())):
        raise ValueError('迁移路径越界')
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, 'is_junction', lambda: False)():
            raise ValueError('迁移不接受符号链接或目录联接')
    return path


def directory(path):
    path = safe_path(path)
    missing = []
    current = path
    while not current.exists():
        missing.append(current)
        current = current.parent
    for item in reversed(missing):
        item.mkdir(mode=0o700)
        os.chmod(item, 0o700)
    return path


def private_json(path, value):
    path = safe_path(path)
    directory(path.parent)
    temp = path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as out:
            json.dump(value, out, ensure_ascii=False, indent=2)
            out.flush()
            os.fsync(out.fileno())
        temp.replace(path)
        os.chmod(path, 0o600)
    finally:
        temp.unlink(missing_ok=True)


def digest_file(path):
    digest = hashlib.sha256()
    with safe_path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


def copy_private(source, target):
    source, target = safe_path(source), safe_path(target)
    if not source.is_file():
        raise ValueError('迁移文件缺失或类型不正确')
    directory(target.parent)
    with source.open('rb') as inp, os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as out:
        shutil.copyfileobj(inp, out, length=1024*1024)
        out.flush()
        os.fsync(out.fileno())
    os.chmod(target, 0o600)


def backup_database(source, target):
    source, target = safe_path(source), safe_path(target)
    if not source.is_file():
        raise ValueError('原曲库数据库不存在')
    for suffix in ('-wal', '-shm'):
        safe_path(Path(str(source)+suffix))
    directory(target.parent)
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb'):
        pass
    src = sqlite3.connect(source.as_uri()+'?mode=ro', uri=True, timeout=30)
    dst = sqlite3.connect(target)
    try:
        src.backup(dst, pages=512, sleep=0.05)
        if dst.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('曲库数据库检查未通过')
        dst.execute('PRAGMA journal_mode=DELETE')
    finally:
        dst.close()
        src.close()
    os.chmod(target, 0o600)


def within_source(source, value, fallback):
    path = Path(value or fallback)
    return safe_path(path if path.is_absolute() else source/path, source)


def export_data(source, out):
    source, out = safe_path(source), safe_path(out)
    if not source.is_dir() or out.exists() or out.is_relative_to(source) or source.is_relative_to(out):
        raise ValueError('请使用原服务目录以外的新导出目录')
    config_file = safe_path(source/'config.json', source)
    config = json.loads(config_file.read_text(encoding='utf-8'))
    if not isinstance(config, dict):
        raise ValueError('原配置格式不正确')
    directory(out)
    os.chmod(out, 0o700)
    exported = {key: config[key] for key in CONFIG_KEYS if key in config}
    private_json(out/'config-export.json', exported)
    state_path = within_source(source, config.get('state'), 'state.db')
    backup_database(state_path, out/'state.db')
    cache = within_source(source, config.get('score_cache_dir'), 'score-cache')
    if cache.exists():
        if not cache.is_dir():
            raise ValueError('原评分缓存目录不正确')
        for folder in cache.iterdir():
            if not re.fullmatch('[0-9a-f]{64}', folder.name):
                continue
            safe_path(folder, source)
            if not folder.is_dir():
                continue
            for name in CACHE_FILES:
                file = safe_path(folder/name, source)
                if file.exists():
                    copy_private(file, out/'score-cache'/folder.name/name)
        shared = within_source(source, config.get('shared_score_cache_dir'), str(cache/'shared'))
        if shared.exists():
            for folder in shared.iterdir():
                if not re.fullmatch('[0-9a-f]{64}', folder.name):
                    continue
                safe_path(folder, source)
                if not folder.is_dir():
                    continue
                for file in folder.iterdir():
                    if re.fullmatch(r'(?:-1|[0-9]|[1-5][0-9]|6[0-3])\.json', file.name):
                        copy_private(safe_path(file, source), out/'score-cache/shared'/folder.name/file.name)
    vault = [safe_path(state_path.parent/name, source) for name in VAULT_FILES]
    if any(path.exists() for path in vault):
        if not all(path.is_file() for path in vault):
            raise ValueError('原音源加密文件与密钥不完整')
        for name, path in zip(VAULT_FILES, vault):
            copy_private(path, out/name)
    files = {}
    for path in out.rglob('*'):
        safe_path(path, out)
        if path.is_file():
            files[path.relative_to(out).as_posix()] = {'bytes': path.stat().st_size, 'sha256': digest_file(path)}
    private_json(out/'export-manifest.json', {'format':FORMAT, 'version':1, 'files':files})
    return {'ok':True, 'files':len(files), 'bytes':sum(entry['bytes'] for entry in files.values())}


def allowed_name(name):
    if not isinstance(name, str) or '\\' in name or PurePosixPath(name).as_posix() != name:
        return False
    if name in ('config-export.json', 'state.db', *VAULT_FILES):
        return True
    return bool(re.fullmatch(r'score-cache/[0-9a-f]{64}/(?:melody-v2\.json|accompaniment\.m4a|online-match\.json)', name)
        or re.fullmatch(r'score-cache/shared/[0-9a-f]{64}/(?:-1|[0-9]|[1-5][0-9]|6[0-3])\.json', name))


def validate_export(root):
    manifest_path = safe_path(root/'export-manifest.json', root)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest.get('format') != FORMAT or manifest.get('version') != 1 or not isinstance(manifest.get('files'), dict):
        raise ValueError('请选择飞牛 K歌正式导出的迁移目录')
    files = manifest['files']
    if not {'state.db','config-export.json'} <= files.keys() or any(not allowed_name(name) for name in files):
        raise ValueError('迁移清单包含未允许的文件或缺少必要文件')
    if any(name in files for name in VAULT_FILES) and not all(name in files for name in VAULT_FILES):
        raise ValueError('音源密钥与加密文件必须一起迁移')
    for path in root.rglob('*'):
        safe_path(path, root)
        if (path.is_file() and path.relative_to(root).as_posix() != 'export-manifest.json'
                and path.relative_to(root).as_posix() not in files):
            raise ValueError('迁移目录含有清单外文件')
    for name, expected in files.items():
        path = safe_path(root/name, root)
        if (not isinstance(expected, dict) or not path.is_file() or path.stat().st_size != expected.get('bytes')
                or digest_file(path) != expected.get('sha256')):
            raise ValueError('迁移文件校验失败，请重新导出')
    exported = json.loads((root/'config-export.json').read_text(encoding='utf-8'))
    if not isinstance(exported, dict) or not exported.keys() <= CONFIG_KEYS:
        raise ValueError('迁移配置包含未允许的设置')
    return files, exported, digest_file(manifest_path)


def empty_destination(path):
    if not path.exists():
        return
    safe_path(path)
    conn = sqlite3.connect(path.as_uri()+'?mode=ro', uri=True)
    try:
        existing = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in ('songs', 'current_guids', 'queue', 'history', 'favorites'):
            if table in existing and conn.execute('SELECT 1 FROM '+table+' LIMIT 1').fetchone():
                raise ValueError('新应用已有曲库、队列或使用记录，为保护数据不能覆盖迁移')
    finally:
        conn.close()


def migrated_config(config, exported, data, digest):
    updated = dict(config)
    # Accept historical export identity fields without replacing the current
    # management account, service sessions or gateway/network configuration.
    updated.update({key: value for key, value in exported.items() if key in SOURCE_CONFIG_KEYS})
    updated.update(state=str(data/'state.db'), score_cache_dir=str(data/'score-cache'),
        shared_score_cache_dir=str(data/'score-cache/shared'), score_generation_enabled=False,
        score_prewarm=False, score_worker_threads=1, migration_export_digest=digest)
    return updated


def import_data(export_root, etc, data, config):
    """Import into a stopped fresh app; lifecycle saves the returned configuration."""
    export_root, etc, data = map(safe_path, (export_root, etc, data))
    if any(export_root.is_relative_to(target) or target.is_relative_to(export_root) for target in (etc, data)):
        raise ValueError('迁移来源必须与应用配置、数据目录分开')
    files, exported, digest = validate_export(export_root)
    directory(etc)
    directory(data)
    completed = safe_path(data/'migration-completed.json', data)
    if completed.exists():
        previous = json.loads(completed.read_text(encoding='utf-8'))
        if previous.get('digest') != digest:
            raise ValueError('此应用已迁移过其他数据，不能覆盖')
        return dict(config) if config.get('migration_export_digest') == digest else migrated_config(config, exported, data, digest)
    pending = safe_path(data/'migration-pending.json', data)
    if pending.exists():
        raise ValueError('检测到中断的迁移，请先恢复迁移备份，不能自动覆盖重试')
    empty_destination(safe_path(data/'state.db', data))
    dataset = ('state.db','state.db-wal','state.db-shm','score-cache','source-secrets')
    for name in dataset:
        safe_path(data/name, data)
    job = str(time.time_ns())+'-'+uuid.uuid4().hex[:8]
    stage = directory(data/('.migration-'+job))
    for name in files:
        if name != 'config-export.json':
            copy_private(export_root/name, stage/name)
    # Recheck copied bytes before touching even the fresh application's data.
    for name, expected in files.items():
        if name != 'config-export.json' and digest_file(stage/name) != expected['sha256']:
            raise ValueError('迁移复制校验失败，原数据与新应用均保持不变')
    staged_db = sqlite3.connect((stage/'state.db').as_uri()+'?mode=ro', uri=True)
    try:
        tables = {row[0] for row in staged_db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {'songs', 'queue'} <= tables or staged_db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('迁移曲库不是有效的飞牛 K歌数据库')
    finally:
        staged_db.close()
    backup = directory(data/'migration-backups'/job)
    private_json(etc/'backups'/('before-migration-'+job+'.json'), config)
    private_json(pending, {'version':1, 'digest':digest, 'backup':str(backup), 'stage':str(stage)})
    moved, installed = [], []
    try:
        for name in dataset:
            target = data/name
            if target.exists():
                target.replace(backup/name)
                moved.append(name)
        for name in ('state.db','score-cache','source-secrets'):
            if (stage/name).exists():
                (stage/name).replace(data/name)
                installed.append(name)
    except BaseException:
        # If rollback is interrupted the pending marker retains exact recovery
        # locations. Never remove original exports, backups or partial stages.
        for name in reversed(installed):
            (data/name).replace(stage/name)
        for name in reversed(moved):
            (backup/name).replace(data/name)
        pending.unlink(missing_ok=True)
        raise
    # The completed marker is the commit record. After this point never roll
    # data back merely because removing an empty staging directory failed.
    private_json(completed, {'version':1, 'digest':digest, 'completedAt':time.time()})
    pending.unlink(missing_ok=True)
    try:
        stage.rmdir()
    except OSError:
        pass
    return migrated_config(config, exported, data, digest)
