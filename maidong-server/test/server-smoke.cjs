const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const { once } = require('node:events');

test('TV/mobile pages, shared queue, and persistence survive server restart', { timeout: 30000 }, async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mdktv-server-'));
  const portProbe = net.createServer();
  portProbe.listen(0, '127.0.0.1'); await once(portProbe, 'listening');
  const port = portProbe.address().port;
  await new Promise(resolve => portProbe.close(resolve));
  let child;
  let logs = '';
  async function stop() {
    if (child && child.exitCode === null) {
      const exited = once(child, 'exit'); child.kill('SIGTERM'); await exited;
    }
  }
  async function start() {
    child = spawn(process.execPath, [path.resolve(__dirname, '../server.js')], {
      env: { ...process.env, PORT: String(port), DISCOVERY_PORT: '0', DATA_DIR: path.join(tmp, 'data'), MUSIC_DIR: path.join(tmp, 'music') },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', b => { logs += b; }); child.stderr.on('data', b => { logs += b; });
    for (let i = 0; i < 100; i++) {
      try { if ((await fetch(`http://127.0.0.1:${port}/api/v1/health`)).ok) return; } catch {}
      if (child.exitCode !== null) throw new Error(logs);
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error('Server did not start: ' + logs);
  }
  async function api(route, method = 'GET', body) {
    const res = await fetch(`http://127.0.0.1:${port}${route}`, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    assert.equal(res.status, 200, route); return res.json();
  }
  try {
    await start();
    assert.equal((await api('/api/v1/health')).app, 'maidong-ktv-server');
    for (const page of ['/tv', '/m', '/tv/assets/hls.min.js']) {
      const res = await fetch(`http://127.0.0.1:${port}${page}`);
      assert.equal(res.status, 200); assert.ok((await res.text()).length > 100);
    }
    assert.equal((await api('/api/v1/queue')).items.length, 0);
    const song = { songId: 'synthetic-test-song', title: 'Local fixture' };
    assert.equal((await api('/api/v1/queue', 'POST', song)).ok, true);
    assert.equal((await api('/api/v1/queue', 'POST', song)).ok, false);
    await stop(); await start();
    assert.equal((await api('/api/v1/queue')).current.songId, song.songId);
    await api('/api/v1/queue/played', 'POST', {});
    const queue = await api('/api/v1/queue');
    assert.equal(queue.items.length, 0); assert.equal(queue.history[0].songId, song.songId);
  } finally { await stop(); fs.rmSync(tmp, { recursive: true, force: true }); }
});
