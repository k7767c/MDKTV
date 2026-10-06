"""Persistent demand-only scoring jobs; playback has priority over NAS analysis."""
import asyncio
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import time
import uuid
from aiohttp import web
from vocal_reference import REFERENCE_VERSION


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        tmp.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False), encoding='utf-8')
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


def read_json(path, default=None):
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
        return value if isinstance(value, dict) else default
    except (OSError, ValueError, UnicodeError):
        return default


def bounded_int(value, default, low, high):
    try:
        return min(high, max(low, int(value)))
    except (ValueError, TypeError, OverflowError):
        return default


class ScoreJobs:
    def __init__(self, bridge):
        self.bridge = bridge
        self.root = Path(bridge.cfg.get('score_cache_dir', 'score-cache')).resolve()
        self.python = Path(bridge.cfg.get('pitch_python', str(Path(__file__).parent/'pitch-venv/bin/python')))
        self.jobs, self.records, self.reference_index, self.playback = {}, {}, {}, {}
        self.lock = asyncio.Lock()
        self.background = None
        self.paused = False
        self.active_sid = None
        self._loaded_root = None
        self._wake = asyncio.Event()
        self._library_cache = None

    @property
    def generation_enabled(self):
        return self.bridge.cfg.get('score_generation_enabled', False) is True

    @property
    def worker_threads(self):
        return bounded_int(self.bridge.cfg.get('score_worker_threads'), 1, 1, 2)

    @property
    def queue_limit(self):
        return bounded_int(self.bridge.cfg.get('score_queue_limit'), 3, 1, 16)

    @property
    def playback_active(self):
        now = time.monotonic()
        self.playback = {device: until for device, until in self.playback.items() if until > now}
        return bool(self.playback)

    def note_playback(self, message):
        """Call only after validating the playback device sending this message."""
        device = str(message.get('_playbackDevice') or 'tv')[:128]
        progress = message.get('progress') if isinstance(message.get('progress'), dict) else message
        stopped = (progress.get('paused') is True or progress.get('playing') is False or
                   progress.get('isPlaying') is False or progress.get('state') in ('paused', 'stopped', 'ended'))
        if stopped:
            self.playback.pop(device, None)
        else:
            self.playback_active
            if len(self.playback) >= 64 and device not in self.playback:
                self.playback.pop(next(iter(self.playback)))
            self.playback[device] = time.monotonic() + 20
        self._wake.set()

    def folder(self, sid):
        _, audio = self.bridge.audio_cache(sid)
        return self.root/audio.name

    def inventory_folder(self, sid):
        """Metadata-only lookup for health/counts, never an authenticated stream lookup."""
        try:
            row = self.bridge.db.execute('SELECT guid,data FROM songs WHERE id=?', (sid,)).fetchone()
        except Exception:
            return self.folder(sid)  # compatible adapters without the catalog schema
        if row is None:
            raise KeyError(sid)
        data = json.loads(row[1])
        key = hashlib.sha256((row[0] + ':' + data.get('revision', '0')).encode()).hexdigest()
        return self.root/key

    @staticmethod
    def cached(folder):
        data = read_json(folder/'melody-v2.json')
        if data is None or data.get('status') != 'ready':
            return None
        notes = data.get('notes')
        if notes is not None and (not isinstance(notes, list) or not notes or any(
                note is not None and (type(note) not in (int, float) or not math.isfinite(note) or not 0 < note < 128)
                for note in notes)):
            return None
        return data

    def current(self, folder):
        try:
            stat = (folder/'melody-v2.json').stat()
        except OSError:
            return False
        stamp = (stat.st_mtime_ns, stat.st_size)
        previous = self.reference_index.get(folder)
        if previous is not None and previous[0] == stamp:
            return previous[1]
        data = self.cached(folder)
        version = data.get('referenceVersion', 0) if data else 0
        valid = type(version) is int and version >= REFERENCE_VERSION
        if len(self.reference_index) >= 1024:
            self.reference_index.clear()
        self.reference_index[folder] = (stamp, valid)
        return valid

    @staticmethod
    def response(data, folder, upgrading=False):
        data = dict(data, accompaniment=(folder/'accompaniment.m4a').is_file(), upgrading=upgrading)
        return web.json_response(data, headers={'Cache-Control': 'no-store' if upgrading or data.get('status') != 'ready' else 'private, max-age=60'})

    @staticmethod
    def _number(value, default):
        return float(value) if type(value) in (int, float) and math.isfinite(value) else default

    def _load(self):
        if self._loaded_root == self.root:
            return
        self._loaded_root = self.root
        saved = read_json(self.root/'jobs-v1.json', {})
        self.paused = saved.get('paused') is True
        self.records = {}
        entries = saved.get('jobs', [])
        if not isinstance(entries, list):
            return
        for item in entries[-128:]:
            if not isinstance(item, dict) or type(item.get('songId')) is not int or item['songId'] <= 0:
                continue
            sid = item['songId']
            try:
                folder = self.inventory_folder(sid)
            except Exception:
                continue
            status = item.get('status')
            if status not in ('queued', 'preparing', 'error', 'ready', 'cancelled') or item.get('cacheKey') != folder.name:
                continue
            self.records[sid] = dict(songId=sid, cacheKey=folder.name, priority=bounded_int(item.get('priority'), 1, 0, 2),
                requestedAt=self._number(item.get('requestedAt'), time.time()), attempts=bounded_int(item.get('attempts'), 0, 0, 20),
                status='queued' if status == 'preparing' else status, nextRetryAt=self._number(item.get('nextRetryAt'), 0))

    def _save(self):
        active = {sid for sid, task in self.jobs.items() if not task.done()}
        terminal = sorted((sid for sid in self.records if sid not in active), key=lambda sid: self.records[sid]['requestedAt'])
        for sid in terminal[:max(0, len(self.records) - 128)]:
            self.records.pop(sid, None)
            self.jobs.pop(sid, None)
        atomic_json(self.root/'jobs-v1.json', {'version': 1, 'paused': self.paused, 'jobs': list(self.records.values())})

    def _status(self, sid, folder):
        data = read_json(folder/'status.json', {'status': 'queued', 'message': '等待旋律处理'})
        record = self.records.get(sid, {})
        if record.get('status') == 'cancelled':
            return {'status': 'cancelled', 'message': '已取消评分准备；再次准备需要手动重试'}
        if record.get('status') == 'error':
            return {'status': 'error', 'message': '评分准备失败，已暂停重试；播放不受影响',
                    'retryAt': record.get('nextRetryAt', 0), 'attempts': record.get('attempts', 0)}
        if sid in self.jobs and not self.jobs[sid].done() and (self.paused or self.playback_active):
            data = dict(data, status='queued', message='播放优先，稍后继续评分准备' if self.playback_active else '评分准备已暂停')
        return data

    async def get(self, sid, priority=1):
        folder = self.folder(sid)
        cached = self.cached(folder)
        if cached is not None or (folder/'accompaniment.m4a').is_file():
            os.utime(folder, None)
        if not self.generation_enabled:
            matching = None
            if not (folder/'accompaniment.m4a').is_file():
                matching = self.bridge.online_tracks.request(sid)
            return self.response(cached or matching or {'status': 'unavailable', 'message': '暂无评分谱，可自由演唱'}, folder)
        if cached is not None and self.current(folder):
            return self.response(cached, folder)
        if not self.python.is_file():
            return self.response(cached or {'status': 'error', 'message': 'NAS 旋律分析组件尚未就绪'}, folder)
        self._load()
        record = self.records.get(sid, {})
        if record.get('status') == 'ready' and ((folder/'melody-v2.json').exists() or time.time()-record.get('requestedAt', 0) > 60):
            # Recover a removed/corrupt reference or a newer reference format;
            # successful task history must never strand the song permanently.
            self.records.pop(sid, None)
            self.jobs.pop(sid, None)
        if sid not in self.jobs and self.records.get(sid, {}).get('status') not in ('error', 'cancelled', 'ready'):
            if not self._enqueue(sid, priority):
                return self.response(cached or {'status': 'busy', 'message': '评分待办已满，请稍后手动准备'}, folder, cached is not None)
        elif sid in self.records and priority < self.records[sid]['priority']:
            self.records[sid]['priority'] = priority
            self._save()
            self._wake.set()
        data = self._status(sid, folder)
        return self.response(cached or data, folder, cached is not None and data.get('status') not in ('error', 'cancelled'))

    def _enqueue(self, sid, priority=1):
        if sid in self.jobs and not self.jobs[sid].done():
            return True
        if sum(not task.done() for task in self.jobs.values()) >= self.queue_limit:
            return False
        folder = self.folder(sid)
        record = self.records.get(sid, {})
        self.records[sid] = dict(songId=sid, cacheKey=folder.name, priority=priority, requestedAt=time.time(),
                                 attempts=record.get('attempts', 0), status='queued', nextRetryAt=0)
        atomic_json(folder/'status.json', {'status': 'queued', 'message': '等待空闲时准备评分'})
        self.jobs[sid] = asyncio.create_task(self._dispatch(sid, folder))
        self._save()
        self._wake.set()
        return True

    async def _wait_tick(self, seconds=1):
        try:
            await asyncio.wait_for(self._wake.wait(), seconds)
        except asyncio.TimeoutError:
            pass
        self._wake.clear()

    async def _dispatch(self, sid, folder):
        record = self.records[sid]
        try:
            while True:
                candidates = [key for key, task in self.jobs.items() if not task.done() and self.records[key]['status'] == 'queued']
                first = min(candidates, key=lambda key: (self.records[key]['priority'], self.records[key]['requestedAt'])) if candidates else None
                if self.generation_enabled and not self.paused and not self.playback_active and self.active_sid is None and first == sid:
                    break
                await self._wait_tick()
            self.active_sid = sid
            record['status'] = 'preparing'
            record['attempts'] += 1
            self._save()
            await self.run(sid, folder)
            if read_json(folder/'status.json', {}).get('status') == 'error':
                raise RuntimeError('worker failed')
            record['status'] = 'ready'
            self._library_cache = None
        except asyncio.CancelledError:
            if record.get('status') != 'cancelled':
                record['status'] = 'queued'
                atomic_json(folder/'status.json', {'status': 'queued', 'message': '重启后等待空闲时继续准备'})
            raise
        except Exception:
            record['status'] = 'error'
            record['nextRetryAt'] = time.time() + min(86400, 300 * 2 ** min(max(0, record['attempts'] - 1), 8))
            atomic_json(folder/'status.json', {'status': 'error', 'message': '评分准备失败，可稍后手动重试'})
            self._library_cache = None
        finally:
            if self.active_sid == sid:
                self.active_sid = None
            self._save()
            self._wake.set()

    async def control(self, action, sid=None):
        self._load()
        if action in ('pause', 'resume'):
            self.paused = action == 'pause'
        elif action == 'cancel':
            for key in ([sid] if sid is not None else list(self.records)):
                if key not in self.records:
                    continue
                self.records[key]['status'] = 'cancelled'
                task = self.jobs.get(key)
                if task is not None and not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
                try:
                    atomic_json(self.inventory_folder(key)/'status.json', {'status': 'cancelled', 'message': '已取消评分准备'})
                except (KeyError, ValueError, web.HTTPException):
                    pass  # cancellation still applies after a source/song disappears
        elif action == 'retry':
            if type(sid) is not int or sid <= 0:
                raise web.HTTPBadRequest(text='请指定需要准备的歌曲')
            if not self.generation_enabled or not self.python.is_file():
                raise web.HTTPConflict(text='评分生成组件未启用或未安装')
            record = self.records.get(sid, {})
            if record.get('status') == 'error' and record.get('nextRetryAt', 0) > time.time():
                raise web.HTTPConflict(text='上次准备失败，冷却后可重试')
            if not self._enqueue(sid, 0):
                raise web.HTTPConflict(text='评分待办已满')
        else:
            raise web.HTTPBadRequest(text='未知评分操作')
        self._save()
        self._wake.set()
        return await self.library_status()

    def worker_env(self, folder):
        threads = str(self.worker_threads)
        return dict(os.environ, OMP_NUM_THREADS=threads, MKL_NUM_THREADS=threads, OPENBLAS_NUM_THREADS=threads,
                    NUMEXPR_NUM_THREADS=threads, VECLIB_MAXIMUM_THREADS=threads, SCORE_WORKER_THREADS=threads,
                    SCORE_SEGMENT_SECONDS='180', TORCH_HOME=str(self.root/'models'), SCORE_LYRICS=str(folder/'lyrics.json'))

    async def _terminate(self, proc, group=False):
        if proc.returncode is not None and not (group and os.name == 'posix'):
            return
        try:
            if group and os.name == 'posix':
                os.killpg(proc.pid, signal.SIGCONT)
                os.killpg(proc.pid, signal.SIGTERM)
            else:
                proc.terminate()
        except ProcessLookupError:
            return
        try:
            await asyncio.wait_for(proc.wait(), 5)
        except asyncio.TimeoutError:
            try:
                if group and os.name == 'posix':
                    os.killpg(proc.pid, signal.SIGKILL)
                else:
                    proc.kill()
            except ProcessLookupError:
                pass
            await asyncio.wait_for(proc.wait(), 5)
        finally:
            # The group leader may exit before ffmpeg/alignment descendants,
            # including when it is killed by the kernel. Reap the owned group
            # even if waiting for the leader completed immediately.
            if group and os.name == 'posix':
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    async def _wait_process(self, proc, timeout, group=True):
        elapsed, suspended = 0, False
        waiter = asyncio.create_task(proc.wait())
        try:
            while not waiter.done():
                should_pause = self.paused or self.playback_active or not self.generation_enabled
                if os.name == 'posix' and group and should_pause != suspended:
                    try:
                        os.killpg(proc.pid, signal.SIGSTOP if should_pause else signal.SIGCONT)
                    except ProcessLookupError:
                        break
                    suspended = should_pause
                try:
                    await asyncio.wait_for(asyncio.shield(waiter), 1)
                except asyncio.TimeoutError:
                    if not suspended:
                        elapsed += 1
                        if elapsed > timeout:
                            raise asyncio.TimeoutError()
            await waiter
        finally:
            if not waiter.done():
                waiter.cancel()
            await asyncio.gather(waiter, return_exceptions=True)

    async def run(self, sid, folder):
        proc, feeder, group = None, None, os.name == 'posix'
        try:
            async with self.lock:
                duration = float(self.bridge.song(sid).get('duration') or 0)
                if not math.isfinite(duration) or duration < 0:
                    raise ValueError('invalid duration')
                if shutil.disk_usage(self.root).free < max(5*1024**3, duration*44100*8*3):
                    raise RuntimeError('insufficient scratch disk space')
                guid, audio = self.bridge.audio_cache(sid)
                lyrics = {}
                if not guid.startswith('video:'):
                    try:
                        listing = await self.bridge.fn('lyric/list', {'trackGUID': guid})
                        items = listing.get('list') or []
                        lyrics = next((x for x in items if x.get('guid') == listing.get('preferred') and x.get('isLRC')), None)
                        lyrics = lyrics or next((x for x in items if x.get('isLRC')), {})
                    except Exception:
                        lyrics = {}
                atomic_json(folder/'lyrics.json', {'content': lyrics.get('content', ''), 'offset': lyrics.get('offset', 0)})
                async def idle():
                    while self.paused or self.playback_active or not self.generation_enabled:
                        await self._wait_tick()
                await idle()
                # Scoring owns its decoder and stream reader. Never launch or
                # cancel the shared HLS converter used by actual playback.
                local = None
                if (audio/'complete').is_file():
                    local = audio/'index.m3u8'
                elif guid.startswith('video:'):
                    local = self.bridge.videos.path(guid)
                    if local is None:
                        raise RuntimeError('video source unavailable')
                source = folder/'input.wav'
                command = ['/usr/bin/ffmpeg', '-v', 'error', '-y',
                    '-threads', '1', '-filter_threads', '1', '-filter_complex_threads', '1',
                    '-i', str(local) if local is not None else 'pipe:0',
                    '-vn', '-threads', '1', '-ac', '2', '-ar', '44100', str(source)]
                if group and Path('/usr/bin/nice').is_file():
                    command = ['/usr/bin/nice', '-n', '10'] + command
                proc = await asyncio.create_subprocess_exec(*command,
                    stdin=asyncio.subprocess.PIPE if local is None else asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, start_new_session=group)
                if local is None:
                    async def feed():
                        try:
                            await self.bridge.feed_audio(guid, proc, before_chunk=idle)
                        finally:
                            proc.stdin.close()
                    feeder = asyncio.create_task(feed())
                await self._wait_process(proc, max(300, duration*2), group)
                if feeder is not None:
                    await feeder
                    feeder = None
                if proc.returncode:
                    raise RuntimeError('decode failed')
                with (folder/'worker.log').open('wb') as log:
                    proc = await asyncio.create_subprocess_exec(str(self.python), str(Path(__file__).with_name('score_worker.py')),
                        str(source), str(folder), stdout=log, stderr=log, env=self.worker_env(folder), start_new_session=group)
                    await self._wait_process(proc, max(3600, duration*16+300), group)
                    if proc.returncode or not self.current(folder):
                        raise RuntimeError('worker failed')
                self.prune(folder)
        finally:
            if feeder is not None:
                feeder.cancel()
                await asyncio.gather(feeder, return_exceptions=True)
            if proc is not None and (proc.returncode is None or (group and proc.returncode != 0)):
                await self._terminate(proc, group)
            (folder/'input.wav').unlink(missing_ok=True)

    def prune(self, current):
        if not self.root.exists():
            return
        folders = [p for p in self.root.iterdir() if p.is_dir() and not p.is_symlink() and len(p.name) == 64 and all(c in '0123456789abcdef' for c in p.name)]
        sizes = {p: (p/'accompaniment.m4a').stat().st_size if (p/'accompaniment.m4a').is_file() else 0 for p in folders}
        total = sum(sizes.values())
        active = {self.folder(sid) for sid, task in self.jobs.items() if not task.done()}
        for p in sorted(folders, key=lambda p: p.stat().st_mtime):
            if total <= int(self.bridge.cfg.get('score_accompaniment_limit_bytes', 2*1024**3)):
                break
            if p == current or p in active or time.time()-p.stat().st_mtime < 3600:
                continue
            if p.resolve().parent == self.root and ((p/'melody-v2.json').is_file() or (p/'online-match.json').is_file()):
                (p/'accompaniment.m4a').unlink(missing_ok=True)
                total -= sizes[p]

    async def prepare_queue(self):
        for song in self.bridge.queue()[:self.queue_limit]:
            sid = song['song_id']
            if not self.generation_enabled:
                if not (self.folder(sid)/'accompaniment.m4a').is_file():
                    self.bridge.online_tracks.request(sid)
            elif self.python.is_file() and not self.current(self.folder(sid)):
                await self.get(sid, priority=0)

    async def prewarm_once(self):
        """Compatibility entry: only explicitly queued songs, never the full library."""
        await self.prepare_queue()

    def start(self):
        if self.background is not None and not self.background.done():
            return
        self._load()
        for sid, record in list(self.records.items()):
            if record['status'] == 'queued' and self.generation_enabled and self.python.is_file():
                try:
                    self._enqueue(sid, record['priority'])
                except Exception:
                    continue  # preserve pending jobs while their source is logged out
        async def loop():
            while True:
                await asyncio.sleep(30)
                try:
                    if self.generation_enabled:
                        for sid, record in list(self.records.items()):
                            if record['status'] == 'queued' and sid not in self.jobs:
                                try:
                                    self._enqueue(sid, record['priority'])
                                except Exception:
                                    continue
                        await self.prepare_queue()
                except Exception:
                    continue
        self.background = asyncio.create_task(loop())

    async def library_status(self):
        # On-demand cached inventory replaces the former 30-second full-library scan.
        now = time.monotonic()
        if self._library_cache is None or now-self._library_cache[0] > 60:
            try:
                ids = [r[0] for r in self.bridge.db.execute('SELECT id FROM songs JOIN current_guids USING(guid)')]
            except Exception:
                ids = [r[0] for r in self.bridge.db.execute('SELECT id FROM songs')]
            ready = failed = 0
            for index, sid in enumerate(ids):
                if index % 64 == 0:
                    await asyncio.sleep(0)
                try:
                    folder = self.inventory_folder(sid)
                except Exception:
                    continue  # an unavailable/stale source cannot break health reporting
                if self.current(folder):
                    ready += 1
                elif read_json(folder/'status.json', {}).get('status') == 'error':
                    failed += 1
            self._library_cache = (now, len(ids), ready, failed)
        _, total, ready, failed = self._library_cache
        active = []
        for sid, task in list(self.jobs.items()):
            if not task.done():
                try:
                    state = self._status(sid, self.inventory_folder(sid))
                except Exception:
                    state = {'status': 'queued', 'message': '等待音源重新可用'}
                active.append(dict(songId=sid, **{key: value for key, value in state.items() if key in ('status', 'message', 'progress', 'processedMs', 'durationMs')}))
        recent = [dict(songId=record['songId'], status=record['status'], attempts=record['attempts'],
                       retryAt=record.get('nextRetryAt', 0), requestedAt=record['requestedAt'])
                  for record in sorted(self.records.values(), key=lambda item: item['requestedAt'], reverse=True)
                  if record['status'] in ('ready', 'error', 'cancelled')][:20]
        data = dict(total=total, ready=ready, pending=max(0, total-ready-failed), failed=failed, active=active,
                    enabled=self.generation_enabled and not self.paused, generationEnabled=self.generation_enabled,
                    referenceVersion=REFERENCE_VERSION, updatedAt=int(time.time()), workerThreads=self.worker_threads if self.generation_enabled else 0,
                    paused=self.paused, playbackActive=self.playback_active, queueLimit=self.queue_limit,
                    policy='on-demand-playback-first', retryPolicy='manual-backoff', concurrency=1,
                    recent=recent, workerAvailable=self.python.is_file())
        atomic_json(self.root/'library-progress.json', data)
        return data

    async def close(self):
        if self.background is not None:
            self.background.cancel()
            await asyncio.gather(self.background, return_exceptions=True)
        tasks = list(self.jobs.values())
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
