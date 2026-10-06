import hashlib
import contextlib
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fnos_lifecycle', ROOT/'fnos/app/lifecycle.py')
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class FnosConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.env = dict(TRIM_APPDEST=str(root/'target'), TRIM_PKGETC=str(root/'etc'),
                        TRIM_PKGVAR=str(root/'var'), wizard_password='test-only-password')

    def tearDown(self):
        self.temp.cleanup()

    def test_new_package_is_unconfigured_and_does_not_start_expensive_work(self):
        cfg = lifecycle.settings(self.env)
        self.assertFalse(cfg['score_generation_enabled'])
        self.assertFalse(cfg['score_prewarm'])
        self.assertEqual(1, cfg['score_worker_threads'])
        self.assertEqual([], cfg['lan_networks'])
        self.assertEqual('', cfg['music_token'])
        self.assertNotIn(self.env['wizard_password'], json.dumps(cfg))
        self.assertEqual(8086, cfg['port'])
        self.assertEqual('/app/feiniu-karaoke', cfg['base_path'])
        self.assertTrue(Path(cfg['state']).is_relative_to(self.env['TRIM_PKGVAR']))

    def test_upgrade_preserves_credentials_queue_paths_and_existing_preferences(self):
        app = lifecycle.Lifecycle(self.env)
        app.configure(initial=True)
        old = json.loads(app.config.read_text(encoding='utf-8'))
        app.env['wizard_password'] = 'replayed-password'
        app.configure(initial=True)
        new = json.loads(app.config.read_text(encoding='utf-8'))
        self.assertEqual(old, new)
        self.assertFalse((app.etc/'backups').exists())

    def test_change_password_revokes_sessions_and_keeps_private_backup(self):
        app = lifecycle.Lifecycle(self.env)
        app.configure(initial=True)
        old = json.loads(app.config.read_text(encoding='utf-8'))
        app.env['wizard_password'] = 'new-test-password'
        app.configure()
        new = json.loads(app.config.read_text(encoding='utf-8'))
        self.assertNotEqual(old['session_key'], new['session_key'])
        self.assertNotEqual(old['password_hash'], new['password_hash'])
        self.assertEqual(old['state'], new['state'])
        backups = list((app.etc/'backups').glob('*.json'))
        self.assertEqual(old, json.loads(backups[0].read_text(encoding='utf-8')))

    def test_bad_wizard_input_does_not_overwrite_configuration(self):
        app = lifecycle.Lifecycle(self.env)
        app.configure(initial=True)
        before = app.config.read_bytes()
        for bad in ('80', '65536', '8086; echo bad'):
            app.env['wizard_port'] = bad
            with self.assertRaises(ValueError):
                app.configure()
            self.assertEqual(before, app.config.read_bytes())

    def test_no_password_uses_nas_login_and_disables_direct_port(self):
        self.env.pop('wizard_password')
        cfg = lifecycle.settings(self.env)
        self.assertTrue(cfg['gateway_sso'])
        self.assertFalse(cfg['tcp_enabled'])
        self.assertEqual('', cfg['password_hash'])

    def test_manifest_gateway_and_wizard_contract(self):
        package = ROOT/'fnos'
        entry = json.loads((package/'app/ui/config').read_text(encoding='utf-8'))['.url']['feiniu-karaoke.main']
        self.assertEqual('/app/feiniu-karaoke', entry['gatewayPrefix'])
        self.assertEqual('app.sock', entry['gatewaySocket'])
        self.assertEqual('url', entry['type'])
        self.assertEqual('/app/feiniu-karaoke/sing', entry['url'])
        self.assertNotIn('port', entry)
        for file in (package/'wizard').iterdir():
            self.assertIsInstance(json.loads(file.read_text(encoding='utf-8')), list)
        for file in (package/'cmd').iterdir():
            self.assertNotIn(b'\r\n', file.read_bytes())
            self.assertNotIn(b'sudo', file.read_bytes())

    def test_last_error_preserves_safe_errno_without_exception_or_configuration_text(self):
        app = lifecycle.Lifecycle(self.env)
        error = PermissionError(errno.EACCES, 'synthetic-private-config-token', '/synthetic/private/path')
        app.record_error('configure',error)
        record = json.loads((app.data/'last-error.json').read_text(encoding='utf-8'))
        self.assertEqual(record['action'],'configure')
        self.assertEqual(record['errno'],errno.EACCES)
        self.assertEqual(set(record),{'version','action','type','locations','message','errno','at'})
        serialized = json.dumps(record)
        self.assertNotIn('synthetic',serialized)
        self.assertNotIn(self.env['wizard_password'],serialized)
        if os.name != 'nt':
            self.assertEqual((app.data/'last-error.json').stat().st_mode & 0o777,0o600)
        app.clear_error('start')
        app.clear_error('status')
        self.assertTrue((app.data/'last-error.json').exists())
        app.clear_error('configure')
        self.assertFalse((app.data/'last-error.json').exists())

    def test_lifecycle_failure_is_persisted_and_matching_success_clears_it(self):
        app = lifecycle.Lifecycle(self.env)
        message = self.env | {'TRIM_TEMP_LOGFILE':str(Path(self.temp.name)/'ui-message.txt')}
        stderr = io.StringIO()
        with patch.dict(os.environ,message), patch('sys.argv',['lifecycle.py','configure']), \
             patch.object(lifecycle,'Lifecycle',return_value=app), contextlib.redirect_stderr(stderr), \
             patch.object(app,'configure',side_effect=ValueError('synthetic-secret-from-config')):
            self.assertEqual(lifecycle.main(),1)
        record_path = app.data/'last-error.json'
        self.assertTrue(record_path.is_file())
        self.assertIsNone(json.loads(record_path.read_text(encoding='utf-8'))['errno'])
        combined = stderr.getvalue()+record_path.read_text(encoding='utf-8')+(Path(self.temp.name)/'ui-message.txt').read_text(encoding='utf-8')
        self.assertNotIn('synthetic-secret-from-config',combined)
        with patch.dict(os.environ,message), patch('sys.argv',['lifecycle.py','configure']), \
             patch.object(lifecycle,'Lifecycle',return_value=app), patch.object(app,'configure'):
            self.assertEqual(lifecycle.main(),0)
        self.assertFalse(record_path.exists())


if __name__ == '__main__':
    unittest.main()
