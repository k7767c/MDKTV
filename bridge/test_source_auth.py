import asyncio
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock
from source_auth import MusicSource, SourceVault, SourceError, source_url
from fn_video import VideoSource


class SourceAuthTests(unittest.IsolatedAsyncioTestCase):
    def test_url_roots_application_and_proxy_prefix(self):
        self.assertEqual(source_url('https://nas.example/proxy/'), 'https://nas.example/proxy/music')
        self.assertEqual(source_url('https://nas.example/proxy/music/'), 'https://nas.example/proxy/music')
        for url in ['https://user:pass@nas/', 'https://nas/?x=1', 'file:///etc/passwd','https://nas/\nother']:
            with self.assertRaises(SourceError): source_url(url)

    def test_vault_is_encrypted_and_restores(self):
        with tempfile.TemporaryDirectory() as tmp:
            vault=SourceVault(tmp); data={'music':{'token':'synthetic-secret','digest':'synthetic-hash'}}
            vault.save(data)
            self.assertNotIn(b'synthetic', vault.file.read_bytes())
            self.assertEqual(SourceVault(tmp).load(), data)

    def source(self):
        self.saved=[]
        owner=SimpleNamespace(persist=lambda:self.saved.append(True))
        return MusicSource(owner, {'token':'old','digest':'hash','autoLogin':True,
            'username':'fixture','url':'https://nas/music','deviceId':'stable','scope':'legacy'})

    async def test_parallel_expiry_one_login_and_one_read_retry(self):
        source=self.source()
        async def raw(path, token='', **kwargs):
            await asyncio.sleep(.01)
            if token=='old':raise SourceError('AUTH_REQUIRED','expired')
            return {'list':[]}
        source.raw=raw;source.authenticate=AsyncMock(return_value='new')
        await asyncio.gather(*(source.call('track/list') for _ in range(8)))
        source.authenticate.assert_awaited_once()
        self.assertEqual(source.state,'authenticated')

    async def test_wrong_password_stops_relogin_and_offline_preserves_session(self):
        source=self.source();source.raw=AsyncMock(side_effect=SourceError('AUTH_REQUIRED','expired'))
        source.authenticate=AsyncMock(side_effect=SourceError('INVALID_CREDENTIALS','wrong'))
        for _ in range(2):
            with self.assertRaises(SourceError):await source.call('track/list')
        source.authenticate.assert_awaited_once();self.assertNotIn('digest',source.data)
        source.raw=AsyncMock(side_effect=SourceError('SOURCE_OFFLINE','offline',True))
        with self.assertRaises(SourceError):await source.call('track/list')
        self.assertEqual(source.data['token'],'old')

    async def test_logout_cannot_be_undone_by_pending_login(self):
        source=self.source();started=asyncio.Event();release=asyncio.Event()
        async def auth(*args):started.set();await release.wait();return 'new'
        source.authenticate=auth
        pending=asyncio.create_task(source.login({'url':'https://nas','username':'fixture','password':'fixture'}))
        await started.wait();source.logout();release.set()
        with self.assertRaises(SourceError):await pending
        self.assertNotIn('token',source.data);self.assertEqual(source.state,'unconfigured')

    async def test_password_utf8_sha256_and_stable_device_no_secret_in_status(self):
        source=self.source();source.authenticate=AsyncMock(return_value='new')
        value={'url':'https://nas','username':'fixture','password':'pass','remember':True}
        await source.login(value)
        args=source.authenticate.call_args.args
        self.assertEqual(args[2],'d74ff0ee8da3b9806b18c877dbf29bbde50b5bd8e4dad7a3a725000feb82e8f1')
        self.assertEqual(args[3],'stable');self.assertNotIn('digest',source.data)
        self.assertNotIn('token',source.status());self.assertNotIn('password',source.status())

    async def test_video_read_only_post_can_reauthenticate_once_without_allowing_mutation_retries(self):
        owner=SimpleNamespace(persist=lambda:None)
        source=VideoSource(owner, {'token':'old','digest':'hash','autoLogin':True,
            'username':'fixture','url':'https://nas/v','deviceId':'stable','scope':'fixture'})
        calls=[]
        async def raw(path, token='', body=None, **kwargs):
            calls.append((path,token,body))
            if token=='old':raise SourceError('AUTH_REQUIRED','expired')
            return {'media_guid':'fixture-media'}
        source.raw=raw
        source.authenticate=AsyncMock(return_value='new')
        self.assertEqual(await source.call('play/info',body={'item_guid':'fixture-item'}), {'media_guid':'fixture-media'})
        self.assertEqual(calls, [('play/info','old',{'item_guid':'fixture-item'}),
                                 ('play/info','new',{'item_guid':'fixture-item'})])
        source.authenticate.assert_awaited_once()
        with self.assertRaises(SourceError):
            await source.call('item/delete',body={'item_guid':'fixture-item'})
        self.assertEqual(len(calls),2)
