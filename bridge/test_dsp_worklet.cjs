'use strict';
// Lifecycle/PCM harness only. This cannot confirm that a real browser loads WASM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'dsp-worklet.js'), 'utf8').replace(/^import .*\n/, '');
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(res => { resolve = res; }); return { promise, resolve }; };
function native({ failMalloc = 0, failProcess = false } = {}) {
  const memory = new ArrayBuffer(65536), calls = { malloc: [], free: [], created: [], destroyed: [], config: [], pitch: [] }; let pointer = 1024, count = 0;
  const module = { HEAP16: new Int16Array(memory), HEAPF32: new Float32Array(memory), calls,
    _malloc(bytes) { calls.malloc.push(bytes); count++; if (count === failMalloc) return 0; const result = pointer; pointer += (bytes + 7) & ~7; return result; },
    _free(pointer) { calls.free.push(pointer); }, _fx_create(rate) { calls.created.push(['voice', rate]); return 7; }, _music_create(rate, channels) { calls.created.push(['music', rate, channels]); return 8; },
    _fx_destroy(handle) { calls.destroyed.push(handle); }, _music_destroy(handle) { calls.destroyed.push(handle); },
    _fx_config(handle, enabled, pointer) { calls.config.push({ role: 'voice', enabled, values: [...module.HEAPF32.subarray(pointer / 4, pointer / 4 + 18)] }); return 1; },
    _music_config(handle, enabled, pointer, preamp) { calls.config.push({ role: 'music', enabled, preamp, values: [...module.HEAPF32.subarray(pointer / 4, pointer / 4 + 10)] }); },
    _music_pitch(handle, pitch) { calls.pitch.push(pitch); }, _fx_latency() { return 120; }, _music_latency() { return 60; },
    _fx_process(handle, input, frames, channels, output) { if (failProcess) throw Error('process fixture'); for (let i = 0; i < frames; i++) { module.HEAP16[output / 2 + 2 * i] = module.HEAP16[input / 2 + i * channels]; module.HEAP16[output / 2 + 2 * i + 1] = module.HEAP16[input / 2 + i * channels + channels - 1]; } },
    _music_process() { if (failProcess) throw Error('process fixture'); }
  }; return module;
}
function environment(createDsp) {
  let Processor;
  class Base { constructor() { this.messages = []; this.port = { postMessage: message => this.messages.push(message), onmessage: null }; } }
  const context = vm.createContext({ createDsp, AudioWorkletProcessor: Base, sampleRate: 48000, Promise, Number, Math, Float32Array, registerProcessor(name, value) { assert.equal(name, 'feiniu-dsp'); Processor = value; } });
  vm.runInContext(source, context); return settings => new Processor({ processorOptions: { wasmBinary: new ArrayBuffer(8), randomBytes: Uint8Array.from({length:65536},(_,i)=>i%256), ...settings } });
}
async function main() {
  let checks = 0;
  let fill;
  const entropy = Uint8Array.from([1,2,3,4,5,6]), entropyProcessor = environment(async options => { fill=options.randomFill; return native(); })({randomBytes:entropy});
  await flush(); const firstBytes = new Uint8Array(2), nextBytes = new Uint8Array(4); fill(firstBytes); fill(nextBytes);
  assert.deepEqual([...firstBytes],[1,2]); assert.deepEqual([...nextBytes],[3,4,5,6]); assert.equal(entropy.every(value=>value===0),true); checks+=3;
  assert.throws(()=>fill(new Uint8Array(1)),/耗尽/); assert.throws(()=>fill(new Uint16Array(1)),/随机源/); checks+=2; entropyProcessor.dispose();
  for (const channels of [1, 2]) {
    const module = native(), create = environment(async options => { assert.equal(options.locateFile('any.wasm'), ''); return module; });
    const processor = create({ role: 'music', channels }); await flush();
    assert.ok(processor.ready); checks++;
    const left = Float32Array.from([.1, .2, -.3, .4]), right = Float32Array.from([.6, -.5, .4, -.3]), output = [new Float32Array(4), new Float32Array(4)];
    assert.equal(processor.process([[left, right].slice(0, channels)], [output]), true); checks++;
    for (let i = 0; i < 4; i++) { assert.ok(Math.abs(output[0][i] - left[i]) <= 1 / 32768); assert.ok(Math.abs(output[1][i] - (channels === 1 ? left[i] : right[i])) <= 1 / 32768); checks += 2; }
    assert.equal(module.calls.malloc.length, 3, 'no new native allocations in process'); checks++;
    processor.port.onmessage({ data: { type: 'configure', bands: Array(10).fill(0), pitch: 12, preamp: 0 } }); assert.equal(module.calls.pitch.at(-1), 12); checks++;
    processor.port.onmessage({ data: { type: 'reset' } }); assert.deepEqual(module.calls.pitch.slice(-2), [0,12]); assert.equal(module.calls.malloc.length,3,'seek/source reset cannot allocate new native buffers'); checks+=2;
    processor.port.onmessage({ data: { type: 'dispose' } }); processor.dispose(); assert.deepEqual(module.calls.destroyed, [8]); assert.equal(new Set(module.calls.free).size, 3); checks += 2;
  }
  const pending = deferred(), delayedModule = native(), delayed = environment(() => pending.promise)({ role: 'voice', channels: 1 });
  const output = [new Float32Array(3), new Float32Array(3)], input = Float32Array.from([.1, .2, .3, .4]);
  assert.equal(delayed.process([[input]], [output]), true); assert.deepEqual([...output[0]], [...input.subarray(0, 3)]); checks += 2;
  delayed.port.onmessage({ data: { type: 'configure', enabled: true, values: Array(18).fill(1) } }); delayed.port.onmessage({ data: { type: 'configure', enabled: true, values: Array(18).fill(2) } });
  pending.resolve(delayedModule); await flush(); assert.equal(delayedModule.calls.config.length, 1); assert.equal(delayedModule.calls.config[0].values[0], 2); checks += 2;
  delayed.port.onmessage({ data: null }); delayed.dispose(); assert.equal(delayed.process([[input]], [output]), false); checks++;

  const pendingDisposed = deferred(), neverAllocated = native(), cancelled = environment(() => pendingDisposed.promise)({ role: 'music' });
  await flush(); cancelled.dispose(); pendingDisposed.resolve(neverAllocated); await flush(); assert.equal(neverAllocated.calls.created.length, 0); assert.equal(cancelled.messages.some(message => message.type === 'ready'), false); checks += 2;
  const partial = native({ failMalloc: 2 }), failed = environment(async () => partial)({ role: 'voice' }); await flush();
  assert.equal(failed.ready, false); assert.equal(failed.disposed, true); assert.equal(partial.calls.free.length, 2); assert.deepEqual(partial.calls.destroyed, [7]); assert.equal(failed.messages.filter(message => message.type === 'error').length, 1); checks += 5;
  const thrown = environment(() => { throw Error('init fixture'); })({ role: 'music' }); await flush(); assert.equal(thrown.messages.filter(message => message.type === 'error').length, 1); assert.equal(thrown.disposed, true); checks += 2;
  const runtimeModule = native({ failProcess: true }), runtime = environment(async () => runtimeModule)({ role: 'music' }); await flush();
  const silent = [new Float32Array(4).fill(1), new Float32Array(4).fill(1)]; assert.equal(runtime.process([[new Float32Array(4)]], [silent]), false); assert.equal(silent[0].every(value => value === 0), true); assert.deepEqual(runtimeModule.calls.destroyed, [8]); assert.equal(runtime.messages.at(-1).type, 'error'); checks += 4;
  process.stdout.write(`DSP worklet lifecycle: ${checks} checks passed (simulated host, not browser acceptance)\n`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
