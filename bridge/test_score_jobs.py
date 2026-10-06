import asyncio
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from aiohttp import web
from score_jobs import ScoreJobs, atomic_json


class SchedulerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE songs(id INTEGER, guid TEXT)')
        db.executemany('INSERT INTO songs VALUES (?,?)', [(n, str(n)) for n in range(1, 201)])
        self.b = SimpleNamespace(cfg=dict(score_generation_enabled=True, score_cache_dir=str(self.root/'scores')),
            db=db, online_tracks=SimpleNamespace(request=Mock(return_value=None)), queue=lambda: [],
            audio_cache=lambda sid: (str(sid), self.root/hashlib.sha256(str(sid).encode()).hexdigest()))
        self.s = ScoreJobs(self.b)
        self.s.python = Path(__file__)
        self.completed = []
        async def complete(sid, folder):
            self.completed.append(sid)
            atomic_json(folder/'melody-v2.json', {'status':'ready', 'referenceVersion':4, 'notes':[60, None]})
            atomic_json(folder/'status.json', {'status':'ready'})
        self.s.run = AsyncMock(side_effect=complete)

    async def asyncTearDown(self):
        await self.s.close()
        self.b.db.close()
        self.tmp.cleanup()

    async def settle(self):
        await asyncio.wait_for(asyncio.gather(*list(self.s.jobs.values())), 3)

    async def test_single_worker_priority_deduplication_and_queue_bound(self):
        self.b.cfg['score_queue_limit'] = 2
        await self.s.control('pause')
        await self.s.get(1)
        await self.s.get(1)
        await self.s.get(2, priority=0)
        self.assertEqual(json.loads((await self.s.get(3)).text)['status'], 'busy')
        await asyncio.sleep(.01)
        self.s.run.assert_not_awaited()
        await self.s.control('resume')
        await self.settle()
        self.assertEqual(self.completed, [2, 1])
        await self.s.get(1)
        self.assertEqual(self.s.run.await_count, 2)

    async def test_playback_wait_is_per_device_and_resumes_without_polling_requests(self):
        self.s.note_playback({'_playbackDevice':'tv', 'paused':False})
        self.s.note_playback({'_playbackDevice':'phone', 'paused':False})
        await self.s.get(1)
        await asyncio.sleep(.01)
        self.s.run.assert_not_awaited()
        self.s.note_playback({'_playbackDevice':'phone', 'paused':True})
        self.assertTrue(self.s.playback_active)
        self.s.note_playback({'_playbackDevice':'tv', 'paused':True})
        await self.settle()
        self.assertEqual(self.completed, [1])
        self.s.note_playback({'paused':False})
        with patch('score_jobs.time.monotonic', return_value=time.monotonic()+21):
            self.assertFalse(self.s.playback_active)

    async def test_pending_jobs_and_pause_survive_restart_and_changed_media_is_ignored(self):
        await self.s.control('pause')
        await self.s.get(1)
        await self.s.get(2)
        await self.s.close()
        original = self.b.audio_cache
        self.b.audio_cache = lambda sid: (str(sid), self.root/('f'*64)) if sid == 2 else original(sid)
        self.s = ScoreJobs(self.b)
        self.s.python = Path(__file__)
        self.s.run = AsyncMock()
        self.s.start()
        self.assertTrue(self.s.paused)
        self.assertIn(1, self.s.jobs)
        self.assertNotIn(2, self.s.jobs)
        await self.s.control('resume')
        await self.settle()
        self.s.run.assert_awaited_once()

    async def test_cancel_is_durable_and_polling_cannot_recreate_the_work(self):
        await self.s.control('pause')
        await self.s.get(1)
        await self.s.control('cancel', 1)
        for _ in range(3):
            self.assertEqual(json.loads((await self.s.get(1)).text)['status'], 'cancelled')
        await self.s.control('resume')
        self.s.run.assert_not_awaited()
        await self.s.control('retry', 1)
        await self.settle()
        self.s.run.assert_awaited_once()

    async def test_failures_require_manual_retry_after_persisted_backoff(self):
        self.s.run = AsyncMock(side_effect=RuntimeError('fake failure'))
        await self.s.get(1)
        await self.settle()
        for _ in range(3):
            self.assertEqual(json.loads((await self.s.get(1)).text)['status'], 'error')
        self.s.run.assert_awaited_once()
        with self.assertRaises(web.HTTPConflict):
            await self.s.control('retry', 1)
        self.s.records[1]['nextRetryAt'] = time.time()-1
        self.s._save()
        self.s.run = AsyncMock()
        await self.s.control('retry', 1)
        await self.settle()
        self.s.run.assert_awaited_once()

    async def test_prewarm_is_demand_only_and_status_inventory_is_cached(self):
        self.b.cfg['score_prewarm'] = True
        await self.s.prewarm_once()
        self.s.run.assert_not_awaited()
        self.assertEqual(self.s.jobs, {})
        with patch.object(self.s, 'current', wraps=self.s.current) as current:
            first = await self.s.library_status()
            await self.s.library_status()
            self.assertEqual(current.call_count, 200)
        self.assertEqual(first['policy'], 'on-demand-playback-first')
        self.assertEqual(first['workerThreads'], 1)
        env = self.s.worker_env(self.root)
        for key in ('OMP_NUM_THREADS','MKL_NUM_THREADS','OPENBLAS_NUM_THREADS','NUMEXPR_NUM_THREADS'):
            self.assertEqual(env[key], '1')
        self.b.cfg['score_worker_threads'] = 64
        self.assertEqual(self.s.worker_threads, 2)

    async def test_corrupt_status_and_reference_are_recovered_without_api_failure(self):
        folder = self.s.folder(1)
        folder.mkdir(parents=True)
        (folder/'status.json').write_text('{broken')
        (folder/'melody-v2.json').write_text('[1, 2]')
        self.assertIsNone(self.s.cached(folder))
        await self.s.get(1)
        await self.settle()
        self.assertTrue(self.s.current(folder))
        (folder/'melody-v2.json').write_text('{"status":"ready","notes":["bad"]}')
        self.assertIsNone(self.s.cached(folder))
        self.assertFalse(self.s.current(folder))
        await self.s.get(1)
        await self.settle()
        self.assertTrue(self.s.current(folder))
        self.assertEqual(self.s.run.await_count, 2)

    async def test_completed_jobs_are_bounded_and_credentials_do_not_enter_manifest(self):
        for sid in range(1, 140):
            await self.s.get(sid)
            await self.s.jobs[sid]
        self.assertLessEqual(len(self.s.jobs), 128)
        self.assertLessEqual(len(self.s.records), 128)
        saved = json.loads((self.s.root/'jobs-v1.json').read_text())
        self.assertEqual(set(saved), {'version','paused','jobs'})
        self.assertNotIn('lyrics', json.dumps(saved))

    async def test_termination_escalates_after_timeout(self):
        proc = SimpleNamespace(returncode=None, terminate=Mock(), kill=Mock(), wait=AsyncMock())
        calls = []
        async def timeout_first(awaitable, timeout):
            calls.append(timeout)
            if len(calls) == 1:
                awaitable.close()
                raise asyncio.TimeoutError()
            return await awaitable
        with patch('score_jobs.asyncio.wait_for', side_effect=timeout_first):
            await self.s._terminate(proc)
        proc.terminate.assert_called_once()
        proc.kill.assert_called_once()
        self.assertEqual(calls, [5, 5])

    async def test_failed_group_leader_does_not_leave_its_children_running(self):
        import signal
        proc = SimpleNamespace(pid=12345, returncode=1, wait=AsyncMock(return_value=1))
        with patch('score_jobs.os.name','posix'), patch('score_jobs.os.killpg',create=True) as send, \
             patch.object(signal,'SIGCONT',18,create=True), patch.object(signal,'SIGTERM',15,create=True), \
             patch.object(signal,'SIGKILL',9,create=True):
            await self.s._terminate(proc,group=True)
        self.assertEqual([call.args for call in send.call_args_list],[(12345,18),(12345,15),(12345,9)])

    async def test_library_inventory_works_with_logged_out_source(self):
        self.b.db.execute('ALTER TABLE songs ADD COLUMN data TEXT')
        self.b.db.execute('UPDATE songs SET data=?', ('{"revision":"2"}',))
        self.b.audio_cache = Mock(side_effect=web.HTTPUnauthorized(text='source logged out'))
        key = hashlib.sha256(b'1:2').hexdigest()
        atomic_json(self.s.root/key/'melody-v2.json', {'status':'ready','referenceVersion':4,'notes':[60]})
        data = await self.s.library_status()
        self.assertEqual((data['total'],data['ready']), (200,1))
        self.assertTrue(data['workerAvailable'])
        self.assertEqual(data['recent'], [])
        self.b.audio_cache.assert_not_called()

    async def test_linux_process_group_suspends_and_resumes_for_playback(self):
        import signal
        self.s.note_playback({'paused':False})
        finished = asyncio.Event()
        proc = SimpleNamespace(pid=12345, returncode=None)
        async def wait():
            await finished.wait()
            return 0
        proc.wait = wait
        signals = []
        def send(pid, value):
            signals.append(value)
            if value == 18:  # SIGCONT, supplied below also on Windows test hosts
                proc.returncode = 0
                finished.set()
        # The real Linux path is exercised with a fake process, never an OS PID.
        with patch('score_jobs.os.name','posix'), patch('score_jobs.os.killpg',side_effect=send,create=True), \
             patch.object(signal,'SIGSTOP',19,create=True), patch.object(signal,'SIGCONT',18,create=True):
            task = asyncio.create_task(self.s._wait_process(proc, 5))
            await asyncio.sleep(.01)
            self.assertEqual(signals, [19])
            self.s.note_playback({'paused':True})
            await asyncio.wait_for(task, 2)
            self.assertEqual(signals, [19,18])

    async def test_source_decode_is_owned_paused_and_cancelled_without_touching_playback(self):
        self.s.run = ScoreJobs.run.__get__(self.s, ScoreJobs)
        self.b.song = lambda sid: {'duration':240}
        self.b.fn = AsyncMock(return_value={'list':[]})
        self.b.transcode = AsyncMock()
        playing_release = asyncio.Event()
        playing = asyncio.create_task(playing_release.wait())
        self.b.transcodes = {'playing-session':playing}
        proc = SimpleNamespace(pid=12345, returncode=None, stdin=SimpleNamespace(close=Mock()))
        feeding = asyncio.Event()
        finished = asyncio.Event()
        chunks = []
        async def feed(guid, child, before_chunk=None):
            self.assertIs(child, proc)
            try:
                while True:
                    await before_chunk()
                    chunks.append(guid)
                    feeding.set()
                    await asyncio.sleep(.002)
            finally:
                finished.set()
        self.b.feed_audio = feed
        async def wait_process(*args):
            await asyncio.Event().wait()
        self.s._wait_process = AsyncMock(side_effect=wait_process)
        self.s._terminate = AsyncMock()
        try:
            with patch('score_jobs.shutil.disk_usage',return_value=SimpleNamespace(free=10**12)), \
                 patch('score_jobs.asyncio.create_subprocess_exec',return_value=proc) as spawn:
                await self.s.get(1)
                await asyncio.wait_for(feeding.wait(),1)
                self.s.note_playback({'_playbackDevice':'fixture-tv','paused':False})
                await asyncio.sleep(.02)
                count=len(chunks)
                await asyncio.sleep(.02)
                self.assertEqual(len(chunks),count)
                self.s.note_playback({'_playbackDevice':'fixture-tv','paused':True})
                await asyncio.sleep(.02)
                self.assertGreater(len(chunks),count)
                await self.s.control('pause')
                await asyncio.sleep(.02)
                count=len(chunks)
                await asyncio.sleep(.02)
                self.assertEqual(len(chunks),count)
                await self.s.control('cancel',1)
                self.assertTrue(finished.is_set())
                self.s._terminate.assert_awaited_once_with(proc,os.name=='posix')
                proc.stdin.close.assert_called_once()
                self.b.transcode.assert_not_awaited()
                self.assertEqual(self.b.transcodes,{'playing-session':playing})
                self.assertFalse(playing.done())
                command=spawn.call_args.args
                self.assertIn('pipe:0',command)
                self.assertEqual(command.count('-threads'),2)
                for option in ('-threads','-filter_threads','-filter_complex_threads'):
                    self.assertEqual(command[command.index(option)+1],'1')
        finally:
            playing.cancel()
            await asyncio.gather(playing,return_exceptions=True)


if __name__ == '__main__':
    unittest.main()
