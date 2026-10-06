"""Read-only local music video adapter using FFmpeg stream metadata, not file labels."""
import asyncio
import hashlib
import json
import os
from pathlib import Path


async def run_media(*args, timeout=30):
    proc = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE,
                                               stderr=asyncio.subprocess.DEVNULL)
    try:
        output, _ = await asyncio.wait_for(proc.communicate(), timeout)
        if proc.returncode:
            raise ValueError('media inspection failed')
        return output
    finally:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()


def has_motion_video(probe):
    # Attached album art is a video stream too, but is not an MV.
    return any(s.get('codec_type') == 'video' and not s.get('disposition', {}).get('attached_pic')
               and s.get('width', 0) > 0 and s.get('height', 0) > 0
               for s in probe.get('streams', []))


class VideoLibrary:
    def __init__(self, db, roots, cache):
        self.db = db
        self.roots = [Path(p).resolve() for p in roots]
        self.cache = Path(cache)
        self.db.execute('CREATE TABLE IF NOT EXISTS video_sources(guid TEXT PRIMARY KEY,path TEXT,revision TEXT,data TEXT)')
        self.cover_lock = asyncio.Lock()
        self.progress = {'checked': 0, 'total': 0}

    def allowed(self, path):
        resolved = path.resolve()
        return any(resolved != root and resolved.is_relative_to(root) for root in self.roots)

    def files(self):
        found = {}
        for root in self.roots:
            if not root.is_dir():
                raise OSError('Video root unavailable')
            def fail(error):
                raise error
            for directory, _, names in os.walk(root, followlinks=False, onerror=fail):
                for name in names:
                    path = Path(directory)/name
                    if path.suffix.lower() not in ('.mp4', '.mkv', '.ts', '.avi', '.mov', '.webm', '.m4v', '.mpg', '.mpeg', '.vob', '.m2ts', '.mts', '.flv', '.wmv'):
                        continue
                    if not self.allowed(path) or not path.is_file():
                        continue
                    stat = path.stat()
                    found[str(path.resolve())] = f'{stat.st_size}:{stat.st_mtime_ns}'
        return found

    async def scan(self):
        rows = {}
        files = await asyncio.to_thread(self.files)
        self.progress = {'checked': 0, 'total': len(files)}
        for name, revision in files.items():
            self.progress['checked'] += 1
            guid = 'video:' + hashlib.sha256(name.encode()).hexdigest()
            old = self.db.execute('SELECT revision,data FROM video_sources WHERE guid=?', (guid,)).fetchone()
            if old and old[0] == revision:
                rows[guid] = json.loads(old[1])
                continue
            try:
                probe = json.loads(await run_media('/usr/bin/ffprobe', '-v', 'error', '-show_streams',
                                                  '-show_format', '-of', 'json', name))
                if not has_motion_video(probe):
                    continue
                stamp = Path(name).stat()
                if revision != f'{stamp.st_size}:{stamp.st_mtime_ns}':
                    raise ValueError('File is still being copied')
                fmt = probe.get('format', {})
                tags = {k.lower(): v for k, v in fmt.get('tags', {}).items()}
                data = dict(title=tags.get('title') or Path(name).stem,
                            artist=tags.get('artist') or '本地视频', genre='MV / 视频', language='未分类',
                            duration=float(fmt.get('duration') or 0), format=fmt.get('format_name', ''),
                            audio_only=False, media_kind='mv', revision=revision, coverId='')
                with self.db:
                    self.db.execute('INSERT OR REPLACE INTO video_sources VALUES(?,?,?,?)',
                                    (guid, name, revision, json.dumps(data, ensure_ascii=False)))
                rows[guid] = data
            except (ValueError, OSError, asyncio.TimeoutError):
                if old:
                    rows[guid] = json.loads(old[1])
                continue
        return rows

    def path(self, guid):
        row = self.db.execute('SELECT path FROM video_sources WHERE guid=?', (guid,)).fetchone()
        if not row:
            return None
        path = Path(row[0])
        if not self.allowed(path) or not path.is_file():
            return None
        return path.resolve()

    async def cover(self, guid):
        path = self.path(guid)
        if path is None:
            return None
        stamp = path.stat()
        key = hashlib.sha256(f'{guid}:{stamp.st_size}:{stamp.st_mtime_ns}'.encode()).hexdigest()
        target = self.cache/(key+'.jpg')
        async with self.cover_lock:
            if not target.is_file():
                self.cache.mkdir(parents=True, exist_ok=True)
                tmp = target.with_suffix('.tmp.jpg')
                try:
                    await run_media('/usr/bin/ffmpeg', '-v', 'error', '-y', '-ss', '1', '-i', str(path),
                                    '-map', '0:v:0', '-frames:v', '1', '-vf', 'scale=512:-2', str(tmp))
                    tmp.replace(target)
                except (ValueError, OSError, asyncio.TimeoutError):
                    tmp.unlink(missing_ok=True)
                    return None
        return target
