import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fnos_migrate', ROOT/'fnos/app/migrate.py')
migrate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migrate)
spec = importlib.util.spec_from_file_location('fnos_lifecycle_migrate_tests', ROOT/'fnos/app/lifecycle.py')
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.old = self.root/'old'
        self.old.mkdir()
        self.export = self.root/'export'
        self.etc, self.data = self.root/'etc', self.root/'data'
        self.config = {'music_url':'http://localhost/music','music_token':'synthetic-private-token',
            'username':'old-user','password_salt':'01'*16,'password_hash':'02'*32,
            'session_key':'never-import-session','lan_networks':['0.0.0.0/0'],
            'score_generation_enabled':True,'pitch_python':'/old/worker/bin/python','video_roots':['/read-only-media']}
        (self.old/'config.json').write_text(json.dumps(self.config), encoding='utf-8')
        self.db = sqlite3.connect(self.old/'state.db')
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA wal_autocheckpoint=0')
        self.db.execute('CREATE TABLE songs(id INTEGER, guid TEXT, data TEXT)')
        self.db.execute('CREATE TABLE queue(id INTEGER, song_id INTEGER)')
        self.db.execute('CREATE TABLE history(id INTEGER, song_id INTEGER, at REAL)')
        self.db.execute("INSERT INTO songs VALUES(1,'legacy-guid','{}')")
        self.db.execute('INSERT INTO queue VALUES(1,1)')
        self.db.execute('INSERT INTO history VALUES(1,1,12345)')
        self.db.commit()
        cache = self.old/'score-cache'/('a'*64)
        cache.mkdir(parents=True)
        (cache/'melody-v2.json').write_text('{"status":"ready"}')
        (cache/'accompaniment.m4a').write_bytes(b'cached-accompaniment')
        (cache/'input.wav').write_bytes(b'do-not-export-input')
        (self.old/'score-cache'/'jobs.json').write_text('{"do-not-import":"running"}')
        shared = self.old/'score-cache/shared'/('b'*64)
        shared.mkdir(parents=True)
        (shared/'-1.json').write_text('{"status":"ready"}')
        self.fresh = {'username':'current-user', 'password_salt':'03'*16, 'password_hash':'04'*32,
            'port':8086, 'bind':'127.0.0.1', 'base_path':'/app/feiniu-karaoke',
            'session_key':'fresh-session', 'gateway_sso':True, 'gateway_socket':'/new/private/app.sock',
            'lan_networks':['192.0.2.0/24'], 'tcp_enabled':False,
            'pitch_python':'/new/private/worker', 'score_generation_enabled':False}

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def test_live_wal_backup_and_allowlisted_cache_preserve_original(self):
        before = (self.old/'config.json').read_bytes()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = migrate.export_data(self.old, self.export)
        self.assertEqual(output.getvalue(), '')
        self.assertTrue(result['ok'])
        copy = sqlite3.connect(self.export/'state.db')
        try:
            self.assertEqual(copy.execute('SELECT guid FROM songs').fetchone()[0], 'legacy-guid')
            self.assertEqual(copy.execute('SELECT count(*) FROM queue').fetchone()[0], 1)
        finally:
            copy.close()
        self.assertEqual((self.old/'config.json').read_bytes(), before)
        values = json.loads((self.export/'config-export.json').read_text())
        self.assertEqual(values['music_token'], 'synthetic-private-token')
        self.assertNotIn('session_key', values)
        self.assertNotIn('lan_networks', values)
        self.assertNotIn('pitch_python', values)
        self.assertTrue((self.export/'score-cache'/('a'*64)/'accompaniment.m4a').exists())
        self.assertTrue((self.export/'score-cache/shared'/('b'*64)/'-1.json').exists())
        self.assertFalse(list(self.export.rglob('input.wav')))
        self.assertFalse(list(self.export.rglob('jobs.json')))

    def test_import_is_backed_up_reuses_cache_and_repeat_does_not_overwrite(self):
        migrate.export_data(self.old, self.export)
        self.data.mkdir()
        fresh_db = sqlite3.connect(self.data/'state.db')
        fresh_db.execute('CREATE TABLE songs(id INTEGER)')
        fresh_db.close()
        # A first app launch creates an empty encrypted vault. A legacy export
        # must remove it into backup so its legacy token can be migrated later.
        (self.data/'source-secrets').mkdir()
        (self.data/'source-secrets'/'old-fresh-marker').write_text('backup-me')
        updated = migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertEqual(updated['music_token'], 'synthetic-private-token')
        self.assertEqual(updated['session_key'], 'fresh-session')
        self.assertEqual(updated['pitch_python'], '/new/private/worker')
        self.assertEqual(updated['port'], 8086)
        self.assertEqual(updated['lan_networks'], self.fresh['lan_networks'])
        self.assertFalse(updated['tcp_enabled'])
        self.assertFalse(updated['score_generation_enabled'])
        self.assertFalse(updated['score_prewarm'])
        self.assertEqual(updated['score_worker_threads'], 1)
        self.assertEqual(len(list((self.etc/'backups').glob('*.json'))), 1)
        self.assertEqual(len(list((self.data/'migration-backups').rglob('state.db'))), 1)
        self.assertEqual(len(list((self.data/'migration-backups').rglob('old-fresh-marker'))), 1)
        self.assertFalse((self.data/'source-secrets').exists())
        updated['username'] = 'changed-after-migration'
        before = (self.data/'state.db').read_bytes()
        repeated = migrate.import_data(self.export, self.etc, self.data, updated)
        self.assertEqual(repeated, updated)
        self.assertEqual((self.data/'state.db').read_bytes(), before)
        self.assertTrue((self.old/'score-cache'/('a'*64)/'accompaniment.m4a').exists())

    def test_import_restores_sources_and_data_without_changing_current_login_or_network(self):
        migrate.export_data(self.old, self.export)
        _, exported, _ = migrate.validate_export(self.export)
        for key in ('username', 'password_salt', 'password_hash'):
            self.assertNotEqual(exported[key], self.fresh[key])
        before = dict(self.fresh)
        updated = migrate.import_data(self.export, self.etc, self.data, self.fresh)
        for key in ('username', 'password_salt', 'password_hash', 'session_key',
                    'port', 'bind', 'base_path', 'gateway_sso', 'gateway_socket',
                    'lan_networks', 'tcp_enabled'):
            self.assertEqual(updated[key], before[key], key)
        self.assertEqual(self.fresh, before)
        self.assertTrue(updated['password_hash'])
        self.assertFalse(updated['tcp_enabled'])
        for key in ('music_url', 'music_token', 'video_roots'):
            self.assertEqual(updated[key], self.config[key], key)
        with contextlib.closing(sqlite3.connect(self.data/'state.db')) as connection:
            self.assertEqual(connection.execute('SELECT guid FROM songs').fetchall(), [('legacy-guid',)])
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM queue').fetchone()[0], 1)
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM history').fetchone()[0], 1)
        self.assertEqual(json.loads(next((self.etc/'backups').glob('*.json')).read_text()), before)

    def test_configure_with_empty_account_fields_preserves_current_identity_during_import(self):
        migrate.export_data(self.old, self.export)
        env = {'TRIM_APPDEST':str(self.root/'target'), 'TRIM_PKGETC':str(self.etc),
               'TRIM_PKGVAR':str(self.data), 'wizard_username':'', 'wizard_password':'',
               'wizard_port':'', 'wizard_import_dir':str(self.export)}
        current = lifecycle.settings(env, self.fresh)
        self.etc.mkdir()
        (self.etc/'config.json').write_text(json.dumps(current))
        app = lifecycle.Lifecycle(env)
        with patch.dict(sys.modules, migrate=migrate), patch.object(app, 'stop') as stop:
            app.configure()
        stop.assert_called_once_with()
        saved = json.loads((self.etc/'config.json').read_text())
        for key in ('username', 'password_salt', 'password_hash', 'session_key'):
            self.assertEqual(saved[key], current[key], key)
        self.assertEqual(saved['music_token'], self.config['music_token'])
        self.assertTrue((self.data/'migration-completed.json').is_file())

    def test_nonempty_destination_refuses_before_replacement(self):
        migrate.export_data(self.old, self.export)
        self.data.mkdir()
        existing = sqlite3.connect(self.data/'state.db')
        existing.execute('CREATE TABLE songs(id INTEGER)')
        existing.execute('INSERT INTO songs VALUES(99)')
        existing.commit()
        existing.close()
        before = (self.data/'state.db').read_bytes()
        with self.assertRaises(ValueError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertEqual((self.data/'state.db').read_bytes(), before)
        self.assertFalse((self.data/'migration-completed.json').exists())

    def test_tampered_export_and_foreign_manifest_path_are_refused(self):
        migrate.export_data(self.old, self.export)
        file = self.export/'config-export.json'
        file.write_text('{"music_token":"different"}')
        with self.assertRaises(ValueError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertFalse((self.data/'state.db').exists())
        manifest_file = self.export/'export-manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest['files']['../foreign'] = {'sha256':'0'*64,'bytes':0}
        manifest_file.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)

    def test_foreign_symlink_is_rejected(self):
        migrate.export_data(self.old, self.export)
        foreign = self.root/'foreign'
        foreign.write_text('never-read')
        link = self.export/'foreign-link'
        try:
            link.symlink_to(foreign)
        except OSError:
            self.skipTest('Host does not permit creating symbolic links')
        with self.assertRaises(ValueError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertFalse((self.data/'state.db').exists())

    def test_vault_key_pair_copied_together_and_incomplete_pair_refused(self):
        keys = self.old/'source-secrets/keys'
        keys.mkdir(parents=True)
        (keys/'sources.key').write_bytes(b'fixture-key')
        (self.old/'source-secrets/sources.enc').write_bytes(b'fixture-encrypted')
        migrate.export_data(self.old, self.export)
        migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertEqual((self.data/'source-secrets/keys/sources.key').read_bytes(), b'fixture-key')
        (self.old/'source-secrets/sources.enc').unlink()
        with self.assertRaises(ValueError):
            migrate.export_data(self.old, self.root/'other-export')

    def test_cli_never_prints_private_config(self):
        result = subprocess.run([sys.executable, str(ROOT/'export_fnos_data.py'), '--source', str(self.old),
                                 '--out', str(self.export)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(json.loads(result.stdout)['ok'])
        for value in ('synthetic-private-token','never-import-session','old-user','01010101','02020202'):
            self.assertNotIn(value, result.stdout+result.stderr)

    def test_interrupted_marker_refuses_blind_retry(self):
        migrate.export_data(self.old, self.export)
        self.data.mkdir()
        (self.data/'migration-pending.json').write_text('{}')
        with self.assertRaises(ValueError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertFalse((self.data/'state.db').exists())

    def test_partial_replace_failure_restores_fresh_database(self):
        migrate.export_data(self.old, self.export)
        self.data.mkdir()
        conn = sqlite3.connect(self.data/'state.db')
        conn.execute('CREATE TABLE songs(id INTEGER)')
        conn.close()
        before = (self.data/'state.db').read_bytes()
        replace = Path.replace
        def fail_cache(path, destination):
            if path.name == 'score-cache' and path.parent.name.startswith('.migration-'):
                raise OSError('synthetic replace failure')
            return replace(path, destination)
        with patch.object(Path, 'replace', fail_cache), self.assertRaises(OSError):
            migrate.import_data(self.export, self.etc, self.data, self.fresh)
        self.assertEqual((self.data/'state.db').read_bytes(), before)
        self.assertFalse((self.data/'migration-completed.json').exists())
        self.assertTrue((self.export/'state.db').exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX permission bits are tested on the NAS')
    def test_export_permissions_are_private(self):
        migrate.export_data(self.old, self.export)
        for path in (self.export, *self.export.rglob('*')):
            self.assertEqual(path.stat().st_mode & 0o777, 0o700 if path.is_dir() else 0o600)


if __name__ == '__main__':
    unittest.main()
