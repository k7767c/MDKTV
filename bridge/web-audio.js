/* Browser singing audio. Pitch detection and PerformanceScore follow the Android
 * implementation; native DSP is loaded only after an explicit audio action. */
export const clamp = (value, low, high) => Math.max(low, Math.min(high, Number(value) || 0));

export function parseLyrics(content = '', adjustmentMs = 0) {
  const source = String(content).replace(/^\uFEFF/, '');
  const offset = Number(source.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0);
  const cues = [], plain = [];
  const millis = match => Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number((match[3] || '').padEnd(3, '0').slice(0, 3));
  for (const line of source.split(/\r?\n/)) {
    const tags = [...line.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
    const body = line.replace(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g, '');
    const cleaned = body.replace(/<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>/g, '');
    const text = cleaned.trim(), leading = cleaned.length - cleaned.trimStart().length;
    const words = []; let removed = 0;
    for (const tag of body.matchAll(/<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>/g)) {
      if (Number(tag[2]) < 60) words.push({ character: clamp(tag.index - removed - leading, 0, text.length), time: millis(tag) - offset + adjustmentMs });
      removed += tag[0].length;
    }
    if (tags.length) {
      for (const tag of tags) if (Number(tag[2]) < 60) {
        const shift = millis(tag) - millis(tags[0]);
        cues.push({ time: millis(tag) - offset + adjustmentMs, text, words: words.map(word => ({ ...word, time: word.time + shift })) });
      }
    } else if (text && !/^\[[a-zA-Z]+:.*\]$/.test(text)) plain.push(text);
  }
  return { cues: cues.sort((a, b) => a.time - b.time), plain: plain.join('\n') };
}

export function lyricIndex(cues, positionMs) {
  let low = 0, high = cues.length;
  while (low < high) { const mid = (low + high) >>> 1; if (cues[mid].time <= positionMs) low = mid + 1; else high = mid; }
  return low - 1;
}

export function lyricHighlight(cues, index, positionMs, durationMs) {
  const cue = cues[index]; if (!cue?.text.length) return 0;
  const end = cues[index + 1]?.time ?? durationMs;
  if (end <= cue.time) return 0;
  const anchors = [{ character: 0, time: cue.time }, ...(cue.words || []), { character: cue.text.length, time: end }].sort((a, b) => a.time - b.time);
  let a = anchors[0];
  for (const b of anchors) {
    if (b.time > positionMs) return clamp(a.character + (b.character - a.character) * clamp((positionMs - a.time) / (b.time - a.time), 0, 1), 0, cue.text.length);
    a = b;
  }
  return positionMs < cue.time ? 0 : cue.text.length;
}

/** Fractional sample-rate box resampling supports 44.1 kHz as well as 48 kHz. */
export function mono8k(samples, rate) {
  const ratio = rate / 8000, result = new Float32Array(Math.floor(samples.length / ratio));
  for (let i = 0; i < result.length; i++) {
    const start = i * ratio, end = (i + 1) * ratio; let total = 0;
    for (let j = Math.floor(start); j < Math.ceil(end); j++) total += samples[j] * Math.max(0, Math.min(j + 1, end) - Math.max(j, start));
    result[i] = total / ratio;
  }
  return result;
}

/** Normalized autocorrelation; the native silence and periodicity thresholds. */
export function detectPitch(samples, rate = 8000) {
  const count = samples.length; if (count < 512) return null;
  let mean = 0; for (const value of samples) mean += value / count;
  const x = Float64Array.from(samples, value => value - mean); let energy = 0;
  for (const value of x) energy += value * value / count;
  if (Math.sqrt(energy) < 260 / 32768) return null;
  const low = Math.floor(rate / 800), high = Math.min(Math.floor(rate / 70), Math.floor(count / 2));
  const values = new Float64Array(high + 1), peaks = [];
  for (let lag = low; lag <= high; lag++) {
    let corr = 0, a = 0, b = 0;
    for (let i = 0; i < count - lag; i++) { corr += x[i] * x[i + lag]; a += x[i] ** 2; b += x[i + lag] ** 2; }
    values[lag] = corr / Math.sqrt(Math.max(1 / (32768 ** 4), a * b));
  }
  for (let lag = low + 1; lag < high; lag++) if (values[lag] >= values[lag - 1] && values[lag] > values[lag + 1]) peaks.push(lag);
  if (!peaks.length) return null;
  const best = Math.max(...peaks.map(lag => values[lag])), lag = peaks.find(value => values[value] >= Math.max(.82, best * .95));
  return lag === undefined ? null : 69 + 12 * Math.log2((rate / lag) / 440);
}

export class PerformanceScore {
  constructor(melody) {
    if (!Array.isArray(melody?.notes) || !melody.notes.length || melody.notes.some(note => note !== null && (!Number.isFinite(note) || note <= 0 || note >= 128))) throw new Error('评分参考数据无效');
    const step = Number(melody.stepMs || 40); if (!Number.isFinite(step) || step <= 0 || step > 1000) throw new Error('评分时间轴无效');
    this.notes = melody.notes; this.stepMs = step; this.durationMs = Number(melody.durationMs) || this.notes.length * step;
    this.samples = new Map(); this.visited = new Set(); this.previous = -1; this.partial = false; this.firstPosition = -1; this.lastPosition = 0;
  }
  breakContinuity(markPartial = false) { this.previous = -1; if (markPartial) this.partial = true; }
  observe(position, pitch) {
    if (position < 0 || position >= this.durationMs) return;
    if (this.firstPosition < 0) { this.firstPosition = position; if (position > 2500) this.partial = true; }
    const i = Math.floor(position / this.stepMs);
    if (this.previous >= 0 && Math.abs(i - this.previous) > 15) this.partial = true;
    if (this.previous >= 0 && i > this.previous && i - this.previous <= 15) for (let j = this.previous + 1; j < i; j++) if (this.notes[j] != null) this.visited.add(j);
    if (this.notes[i] != null) { this.visited.add(i); this.samples.set(i, Number.isFinite(pitch) ? pitch : null); }
    this.previous = i; this.lastPosition = position;
  }
  static cents(actual, target) { let delta = (actual - target) % 12; if (delta > 6) delta -= 12; else if (delta < -6) delta += 12; return delta * 100; }
  static accuracy(actual, target) { return Math.exp(-(PerformanceScore.cents(actual, target) ** 2) / (2 * 85 ** 2)); }
  actualAt(ms) { return ms < 0 ? null : this.samples.get(Math.floor(ms / this.stepMs)) ?? null; }
  result(from = 0, to = Infinity) {
    const indexes = [...this.visited].filter(i => i * this.stepMs >= from && i * this.stepMs < to), voiced = indexes.filter(i => this.samples.get(i) != null), whole = to === Infinity;
    if (indexes.length < (whole ? 50 : 8) || voiced.length < (whole ? 25 : 5)) return { score: null, pitch: 0, rhythm: 0, coverage: 0, frames: voiced.length, grade: '待演唱', partial: this.partial };
    const pitch = voiced.reduce((sum, i) => sum + PerformanceScore.accuracy(this.samples.get(i), this.notes[i]), 0) / voiced.length, coverage = voiced.length / indexes.length;
    const starts = indexes.filter(i => i === 0 || [1, 2, 3, 4].every(delta => this.notes[i - delta] == null));
    const rhythm = starts.length ? starts.reduce((sum, i) => {
      const distances = []; for (let delta = -7; delta <= 7; delta++) if (this.samples.get(i + delta) != null) distances.push(Math.abs(delta));
      return sum + (distances.length ? Math.exp(-((Math.min(...distances) * this.stepMs / 130) ** 2) / 2) : 0);
    }, 0) / starts.length : coverage;
    const score = clamp(Math.round(100 * (.70 * pitch + .15 * rhythm + .15 * coverage) * Math.sqrt(coverage)), 0, 100);
    return { score, pitch: Math.round(pitch * 100), rhythm: Math.round(rhythm * 100), coverage: Math.round(coverage * 100), frames: voiced.length, grade: score >= 90 ? 'S' : score >= 80 ? 'A' : score >= 65 ? 'B' : 'C', partial: this.partial };
  }
}

export const VOICE_PARAMETERS = [
  ['变调', -12, 12, 1, 0, '半音'], ['音色 / 声线', -12, 12, 1, 0, '半音'], ['混响量', 0, 70, 1, 0, '%'], ['混响尾音', .2, 6, .1, 1.2, '秒'],
  ['回声量', 0, 60, 1, 0, '%'], ['回声间隔', 60, 600, 10, 160, 'ms'], ['合唱量', 0, 70, 1, 0, '%'], ['声场宽度', 0, 100, 1, 50, '%'],
  ['低音', -12, 12, 1, 0, 'dB'], ['高音', -12, 12, 1, 0, 'dB'], ['动态压缩', 0, 100, 1, 0, '%'], ['饱和 / 失真', 0, 70, 1, 0, '%'],
  ['电音修音强度', 0, 100, 1, 0, '%'], ['电音主音', 0, 11, 1, 0, ''], ['电音音阶', 0, 2, 1, 0, ''], ['黑胶暖化', 0, 100, 1, 0, '%'],
  ['魔音调制', 0, 100, 1, 0, '%'], ['效果输出', -12, 6, 1, 0, 'dB']
].map(([name, min, max, step, initial, unit]) => ({ name, min, max, step, initial, unit }));

const PRESETS = {
  original: ['原声', {}], folk: ['民歌', { 2: 24, 3: 1.6, 9: 3, 10: 30 }], cartoon: ['卡通', { 0: 7, 1: 5, 2: 8, 9: 2 }], monster: ['怪兽', { 0: -8, 1: -7, 8: 4, 11: 18, 17: -3 }],
  slow: ['慢歌', { 2: 34, 3: 2.8, 4: 12, 5: 280, 10: 25 }], fast: ['快歌', { 2: 13, 3: .8, 4: 8, 5: 100, 9: 2, 10: 45 }], host: ['主持', { 8: 2, 9: 3, 10: 65 }], super: ['超级混响', { 2: 58, 3: 5.2, 4: 20, 5: 330, 7: 100, 17: -3 }],
  ktv: ['KTV', { 2: 28, 3: 1.8, 4: 12, 5: 180, 10: 35 }], magnetic: ['磁性', { 1: -3, 8: 4, 9: -2, 10: 55, 2: 10 }], distant: ['远方', { 2: 56, 3: 3.8, 4: 22, 5: 340, 8: -5, 9: -5, 17: -4 }], singer: ['歌神', { 2: 26, 3: 2.1, 6: 8, 9: 2, 10: 45 }],
  concert: ['演唱会', { 2: 43, 3: 3.5, 4: 18, 5: 250, 7: 100, 10: 35 }], wide: ['宽阔人声', { 2: 18, 3: 1.4, 6: 38, 7: 100 }], electro: ['电音', { 12: 100, 2: 12, 3: 1.1, 10: 50 }], vinyl: ['复古黑胶', { 15: 75, 11: 12, 9: -6, 8: 2, 17: -2 }],
  male: ['男生', { 0: -3, 1: -4, 2: 10, 8: 2 }], female: ['女生', { 0: 3, 1: 4, 2: 10, 9: 2 }], mc: ['MC主持', { 8: 4, 9: 3, 10: 80, 11: 10, 4: 8, 5: 100 }], magic: ['魔音', { 0: -4, 1: -5, 16: 65, 4: 18, 5: 200, 17: -3 }],
  choir: ['合唱', { 6: 62, 7: 100, 2: 25, 3: 2.5, 17: -2 }], cat: ['猫咪风格', { 0: 10, 1: 8, 9: 4, 8: -5, 6: 8 }], dog: ['狗狗风格', { 0: -7, 1: -8, 8: 5, 11: 25, 10: 55, 17: -3 }]
};
export function voicePreset(id) {
  const preset = Object.hasOwn(PRESETS, id) ? PRESETS[id] : null; if (!preset) throw new Error('未知人声音效');
  const values = VOICE_PARAMETERS.map(param => param.initial);
  for (const [index, value] of Object.entries(preset[1])) values[Number(index)] = value;
  return { enabled: id !== 'original', preset: id, values };
}

const FALLBACK_EFFECTS = [{ id: 'original', label: '原声' }, { id: 'web-room', label: '房间混响' }, { id: 'web-hall', label: '大厅混响' }, { id: 'web-echo', label: '回声' }, { id: 'web-chorus', label: '合唱' }, { id: 'web-compressor', label: '动态压缩' }];
const EQ_HZ = [60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000];

export class KaraokeAudio {
  constructor(media, callbacks = {}) {
    this.media = media; this.callbacks = callbacks; this.context = null; this.stream = null; this.monitor = false; this.closed = false; this.micGeneration = 0;
    this.effect = 'original'; this.voice = voicePreset('original'); this.eq = { bass: 0, mid: 0, treble: 0 }; this.bands = Array(10).fill(0); this.preamp = 0; this.pitch = 0; this.volume = 1; this.micVolume = 1;
    this.native = false; this.nativeAttempt = null; this.micNodes = []; this.extraSources = new Map();
    this.preferencesGeneration = 0;
  }
  getEffects() { return this.native ? Object.entries(PRESETS).map(([id, value]) => ({ id, label: value[0] })) : FALLBACK_EFFECTS; }
  async ensure({ retryNative = false } = {}) {
    if (this.closed) throw new Error('播放器已关闭');
    if (!this.context) {
      const AudioContext = globalThis.AudioContext || globalThis.webkitAudioContext;
      if (!AudioContext) throw new Error('当前浏览器不支持音频处理');
      this.context = new AudioContext({ latencyHint: 'interactive' });
      this.filters = EQ_HZ.map(hz => { const filter = this.context.createBiquadFilter(); filter.type = 'peaking'; filter.frequency.value = Math.min(hz, this.context.sampleRate * .44); filter.Q.value = 1; return filter; });
      this.musicGain = this.context.createGain(); this.musicGain.gain.value = 1;
    }
    if (this.context.state !== 'running' && this.context.state !== 'closed') await this.context.resume();
    if (this.closed) throw new Error('播放器已关闭');
    if (this.context.state !== 'running') throw new Error('浏览器尚未允许音频处理，请点击播放后重试');
    // Only capture media after resume succeeds. A denied audio action must not
    // silence a song that was playing directly through the media element.
    if (!this.source) { this.source = this.context.createMediaElementSource(this.media); this.reconnectMusic(); this.applyEq(); }
    if (retryNative && !this.native && !this.nativeLoading) this.nativeAttempt = null;
    if (!this.nativeAttempt) { this.nativeLoading = true; this.nativeAttempt = this.loadNative().catch(error => { this.nativeFailure(this.nativeStage || 'load', error); return false; }).finally(() => { this.nativeLoading = false; }); }
    await this.nativeAttempt;
    if (this.closed) throw new Error('播放器已关闭');
    this.callbacks.onReady?.();
    return this.context;
  }
  nativeFailure(stage, error) {
    const clean = value => String(value || '').replace(/https?:\/\/[^\s)]+/gi, '[media address]').slice(0, 600);
    this.nativeDiagnostic = { stage, sampleRate: this.context?.sampleRate, name: clean(error?.name || 'Error'), message: clean(error?.message || String(error)), detail: clean(error?.diagnostic || '') };
    globalThis.console?.warn?.('[飞牛K歌 DSP]', this.nativeDiagnostic);
  }
  async loadNative() {
    const ctx = this.context;
    this.nativeStage = 'features';
    if (!ctx.audioWorklet || !globalThis.AudioWorkletNode || !globalThis.fetch) { this.nativeFailure('features', new Error('AudioWorklet 或媒体请求能力不可用')); return false; }
    this.nativeStage = 'wasm';
    const response = await fetch(new URL('./vendor/feiniu-dsp.wasm', import.meta.url), { credentials: 'same-origin' });
    if (!response.ok) { this.nativeFailure('wasm', new Error('HTTP ' + response.status)); return false; }
    const wasmBinary = await response.arrayBuffer(); if (this.closed) return false;
    this.nativeStage = 'addModule';
    await ctx.audioWorklet.addModule(new URL('./dsp-worklet.js', import.meta.url)); if (this.closed) return false;
    this.nativeStage = 'nodes';
    const create = role => new Promise((resolve, reject) => {
      if (!globalThis.crypto?.getRandomValues) { reject(new Error('当前浏览器不能安全初始化原生音效随机源')); return; }
      // AudioWorklet has no crypto API. Supply a private, one-use entropy pool
      // from this page's Web Crypto; it is never logged or sent to the NAS.
      const randomBytes = new Uint8Array(65536); globalThis.crypto.getRandomValues(randomBytes);
      let node;
      try { node = new AudioWorkletNode(ctx, 'feiniu-dsp', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2], processorOptions: { role, channels: 2, wasmBinary, randomBytes } }); }
      finally { randomBytes.fill(0); } // The processor receives a structured clone.
      let settled = false;
      const fail = error => { if (settled) return; settled = true; clearTimeout(timeout); node.port.postMessage({ type: 'dispose' }); node.disconnect(); node.port.onmessage = null; node.onprocessorerror = null; reject(error); };
      const timeout = setTimeout(() => fail(new Error('原生音效启动超时')), 8000);
      node.port.onmessage = ({ data }) => {
        if (data?.type === 'ready') { if (this.closed) { fail(new Error('播放器已关闭')); return; } settled = true; clearTimeout(timeout); resolve(node); }
        if (data?.type === 'error') fail(Object.assign(new Error(data.message || '原生音效启动失败'), { diagnostic: data.diagnostic || '' }));
      };
      node.onprocessorerror = () => fail(new Error('原生音效处理器停止'));
    });
    const nodes = await Promise.allSettled([create('music'), create('voice')]);
    if (this.closed || nodes.some(result => result.status === 'rejected')) { for (const [index, result] of nodes.entries()) { if (result.status === 'fulfilled') { result.value.port.postMessage({ type: 'dispose' }); result.value.disconnect(); } else if (!this.closed) this.nativeFailure(index === 0 ? 'music node' : 'voice node', result.reason); } return false; }
    this.nativeMusic = nodes[0].value; this.nativeVoice = nodes[1].value; this.native = true;
    for (const node of [this.nativeMusic, this.nativeVoice]) node.port.onmessage = ({ data }) => {
      if (data?.type === 'configured') this.callbacks.onLatency?.({ role: node === this.nativeMusic ? 'music' : 'voice', latencyMs: data.latencyMs || 0 });
      if (data?.type === 'error') this.disableNative(new Error(data.message || '原生音效处理失败'));
    };
    for (const node of [this.nativeMusic, this.nativeVoice]) node.onprocessorerror = () => this.disableNative(new Error('原生音效处理器停止，已恢复浏览器音频'));
    this.reconnectMusic(); this.applyEq(); this.applyVoice();
    this.callbacks.onCapabilities?.({ nativeDsp: true, pitch: true, voiceEffects: true, equalizer: true });
    this.nativeStage = 'ready'; this.nativeDiagnostic = null;
    return true;
  }
  disableNative(error) {
    if (!this.native) return;
    this.native = false; this.pitch = 0; this.effect = 'original'; this.voice = voicePreset('original');
    for (const node of [this.nativeMusic, this.nativeVoice]) { node.port.onmessage = null; node.onprocessorerror = null; node.port.postMessage({ type: 'dispose' }); node.disconnect(); }
    this.nativeMusic = this.nativeVoice = null;
    if (!this.closed && this.context?.state !== 'closed') { this.reconnectMusic(); this.applyEq(); this.applyVoice(); }
    this.callbacks.onLatency?.({ role: 'music', latencyMs: 0 }); this.callbacks.onLatency?.({ role: 'voice', latencyMs: 0 });
    this.callbacks.onCapabilities?.({ nativeDsp: false, pitch: false, voiceEffects: true, equalizer: true }); this.callbacks.onError?.(error);
  }
  reconnectMusic() {
    this.source?.disconnect(); for (const source of this.extraSources.values()) source.disconnect();
    for (const filter of this.filters) filter.disconnect(); this.nativeMusic?.disconnect(); this.musicGain.disconnect();
    const first = this.nativeMusic || this.filters[0]; this.source?.connect(first); for (const source of this.extraSources.values()) source.connect(first);
    if (this.nativeMusic) this.nativeMusic.connect(this.musicGain);
    else { for (let i = 0; i < this.filters.length - 1; i++) this.filters[i].connect(this.filters[i + 1]); this.filters.at(-1).connect(this.musicGain); }
    this.musicGain.connect(this.context.destination);
  }
  connectExtra(media) { if (!this.context || this.extraSources.has(media)) return; const source = this.context.createMediaElementSource(media); this.extraSources.set(media, source); source.connect(this.nativeMusic || this.filters[0]); }
  resetMusic() { this.nativeMusic?.port.postMessage({ type: 'reset' }); }
  preferencesCurrent(generation) { if (generation !== this.preferencesGeneration) { const error = new Error('音频设置所属账号已变化'); error.name = 'AbortError'; throw error; } }
  resetCachedSettings(settings = {}) {
    ++this.preferencesGeneration;
    this.pitch = clamp(settings.pitch ?? 0, -12, 12); this.preamp = clamp(settings.preamp ?? 0, -20, 12);
    this.eq = { bass: clamp(settings.eq?.bass ?? 0, -12, 12), mid: clamp(settings.eq?.mid ?? 0, -12, 12), treble: clamp(settings.eq?.treble ?? 0, -12, 12) };
    this.bands = Array.isArray(settings.bands) && settings.bands.length === 10 ? settings.bands.map(value => clamp(value, -12, 12)) : [this.eq.bass, this.eq.bass, this.eq.bass / 2, this.eq.mid, this.eq.mid, this.eq.mid, this.eq.treble / 2, this.eq.treble, this.eq.treble, this.eq.treble];
    const id = settings.effect || 'original', custom = settings.voiceParameters;
    if (id === 'custom' && Array.isArray(custom) && custom.length === 18 && custom.every((value, i) => Number.isFinite(value) && value >= VOICE_PARAMETERS[i].min && value <= VOICE_PARAMETERS[i].max) && !(custom[13] % 1) && !(custom[14] % 1)) { this.effect = 'custom'; this.voice = { enabled: true, preset: 'custom', values: [...custom] }; }
    else if (Object.hasOwn(PRESETS, id)) { this.effect = id; this.voice = voicePreset(id); }
    else { this.effect = !this.native && FALLBACK_EFFECTS.some(effect => effect.id === id) ? id : 'original'; this.voice = voicePreset('original'); }
    this.setMicVolume(settings.micVolume ?? .7);
    // Staging on initial page/account load never starts an AudioContext.
    if (this.context && !this.closed) { this.applyEq(); this.applyVoice(); }
  }
  async setPitch(value) { const generation = this.preferencesGeneration; this.pitch = clamp(value, -12, 12); await this.ensure(); this.preferencesCurrent(generation); if (!this.native) { this.pitch = 0; throw new Error('当前浏览器未能加载原生变调模块'); } this.applyEq(); }
  async setEq(settings = {}) {
    const generation = this.preferencesGeneration;
    if (Array.isArray(settings.bands)) { if (settings.bands.length !== 10) throw new Error('均衡器需要 10 个频段'); this.bands = settings.bands.map(value => clamp(value, -12, 12)); }
    else { this.eq = { ...this.eq, ...settings }; this.bands = [this.eq.bass, this.eq.bass, this.eq.bass / 2, this.eq.mid, this.eq.mid, this.eq.mid, this.eq.treble / 2, this.eq.treble, this.eq.treble, this.eq.treble].map(value => clamp(value, -12, 12)); }
    this.preamp = clamp(settings.preamp ?? this.preamp, -20, 12); await this.ensure(); this.preferencesCurrent(generation); this.applyEq();
  }
  applyEq() {
    if (!this.context) return;
    const enabled = this.bands.some(value => Math.abs(value) > .001) || this.preamp !== 0;
    const preamp = clamp(this.preamp - Math.max(0, ...this.bands), -20, 12);
    if (this.native) { this.nativeMusic.port.postMessage({ type: 'configure', enabled, bands: [...this.bands], preamp, pitch: this.pitch }); this.musicGain.gain.value = 1; }
    else { this.filters.forEach((filter, index) => filter.gain.setTargetAtTime(this.bands[index], this.context.currentTime, .01)); this.musicGain.gain.value = 10 ** (preamp / 20); }
  }
  async setMicrophone(enabled, { monitor = false, deviceId = '' } = {}) {
    const generation = ++this.micGeneration; this.stopMicrophone(false);
    if (!enabled) return { enabled: false, monitoring: false };
    if (globalThis.isSecureContext === false) throw new Error('麦克风需要 HTTPS 安全连接，请通过飞牛 NAS 的 HTTPS 地址打开');
    if (!globalThis.navigator?.mediaDevices?.getUserMedia) throw new Error('当前浏览器不能访问麦克风，请使用支持麦克风的浏览器');
    const ctx = await this.ensure(); if (generation !== this.micGeneration || this.closed) return { enabled: false, monitoring: false };
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { ...(deviceId ? { deviceId: { exact: deviceId } } : {}), channelCount: 1, echoCancellation: !monitor, noiseSuppression: !monitor, autoGainControl: false }, video: false }); }
    catch (error) { throw new Error(error.name === 'NotAllowedError' ? '麦克风权限未允许，请在浏览器权限中开启' : error.name === 'NotFoundError' ? '没有找到可用的麦克风' : '麦克风无法启动：' + (error.message || error.name)); }
    if (generation !== this.micGeneration || this.closed || globalThis.document?.hidden) { stream.getTracks().forEach(track => track.stop()); return { enabled: false, monitoring: false }; }
    this.stream = stream; this.monitor = Boolean(monitor); this.micSource = ctx.createMediaStreamSource(stream); this.analyser = ctx.createAnalyser(); this.analyser.fftSize = 4096;
    this.micSource.connect(this.analyser); this.micNodes.push(this.micSource, this.analyser);
    this.samples = new Float32Array(this.analyser.fftSize); this.micOutput = ctx.createGain(); this.micOutput.gain.value = this.micVolume; this.micNodes.push(this.micOutput);
    this.applyVoice();
    this.micTimer = setInterval(() => {
      if (!this.stream || this.closed) return;
      this.analyser.getFloatTimeDomainData(this.samples); let energy = 0; for (const value of this.samples) energy += value ** 2 / this.samples.length;
      const pitch = detectPitch(mono8k(this.samples, ctx.sampleRate));
      this.callbacks.onFrame?.({ pitch, level: clamp(Math.sqrt(energy) * 4, 0, 1), delayMs: this.samples.length / ctx.sampleRate * 500 });
    }, 40);
    for (const track of stream.getAudioTracks()) track.addEventListener('ended', () => { if (this.stream === stream) { this.stopMicrophone(); this.callbacks.onMicEnded?.(); } });
    return { enabled: true, monitoring: this.monitor };
  }
  stopMicrophone(invalidate = true) {
    if (invalidate) ++this.micGeneration;
    clearInterval(this.micTimer); this.micTimer = null;
    const stream = this.stream; this.stream = null; stream?.getTracks().forEach(track => track.stop());
    this.micSource?.disconnect(); this.nativeVoice?.disconnect(); this.micOutput?.disconnect();
    this.nativeVoice?.port.postMessage({ type: 'configure', enabled: false, values: VOICE_PARAMETERS.map(value => value.initial) });
    this.effectNodes?.forEach(node => { try { node.disconnect(); node.stop?.(); } catch (_) {} }); this.effectNodes = [];
    for (const node of this.micNodes) { try { node.disconnect(); } catch (_) {} } this.micNodes = [];
    this.micSource = this.analyser = this.micOutput = null; this.monitor = false;
  }
  setMicVolume(value) { this.micVolume = clamp(value, 0, 1); if (this.micOutput) this.micOutput.gain.setTargetAtTime(this.micVolume, this.context.currentTime, .01); }
  async setEffect(id) { const generation = this.preferencesGeneration; await this.ensure(); this.preferencesCurrent(generation); if (!this.getEffects().some(effect => effect.id === id)) throw new Error('该人声音效尚未在当前浏览器可用'); this.effect = id; if (this.native) this.voice = voicePreset(id); this.applyVoice(); }
  async setVoiceParameters(values) {
    const generation = this.preferencesGeneration;
    if (!Array.isArray(values) || values.length !== 18 || values.some((value, i) => !Number.isFinite(value) || value < VOICE_PARAMETERS[i].min || value > VOICE_PARAMETERS[i].max) || values[13] % 1 || values[14] % 1) throw new Error('人声音效参数无效');
    await this.ensure(); this.preferencesCurrent(generation); if (!this.native) throw new Error('自定义人声音效需要原生音效模块'); this.effect = 'custom'; this.voice = { enabled: true, preset: 'custom', values: [...values] }; this.applyVoice();
  }
  applyVoice() {
    if (!this.context) return;
    if (this.native) this.nativeVoice.port.postMessage({ type: 'configure', enabled: this.voice.enabled, values: [...this.voice.values] });
    if (!this.micSource) return;
    this.micSource.disconnect(); this.micSource.connect(this.analyser); this.nativeVoice?.disconnect(); this.micOutput.disconnect();
    this.effectNodes?.forEach(node => { try { node.disconnect(); node.stop?.(); } catch (_) {} }); this.effectNodes = [];
    if (!this.monitor) return; // Capture and score never silently enable live microphone monitoring.
    const ctx = this.context;
    if (this.native) { this.micSource.connect(this.nativeVoice); this.nativeVoice.connect(this.micOutput); }
    else {
      const input = ctx.createGain(); this.micSource.connect(input); this.effectNodes.push(input);
      if (this.effect === 'web-room' || this.effect === 'web-hall') {
        const convolver = ctx.createConvolver(), wet = ctx.createGain(), dry = ctx.createGain(), seconds = this.effect === 'web-hall' ? 3.5 : 1.2;
        const impulse = ctx.createBuffer(2, Math.ceil(ctx.sampleRate * seconds), ctx.sampleRate);
        let seed = 1789; for (let c = 0; c < 2; c++) { const data = impulse.getChannelData(c); for (let i = 0; i < data.length; i++) { seed = (Math.imul(seed, 1664525) + 1013904223) | 0; data[i] = seed / 2147483648 * (1 - i / data.length) ** 2; } }
        convolver.buffer = impulse; wet.gain.value = .25; dry.gain.value = .85; input.connect(dry); dry.connect(this.micOutput); input.connect(convolver); convolver.connect(wet); wet.connect(this.micOutput); this.effectNodes.push(convolver, wet, dry);
      } else if (this.effect === 'web-echo' || this.effect === 'web-chorus') {
        const delay = ctx.createDelay(1), wet = ctx.createGain(), feedback = ctx.createGain(); delay.delayTime.value = this.effect === 'web-echo' ? .18 : .02; wet.gain.value = .25; feedback.gain.value = this.effect === 'web-echo' ? .28 : 0;
        input.connect(this.micOutput); input.connect(delay); delay.connect(wet); wet.connect(this.micOutput); delay.connect(feedback); feedback.connect(delay); this.effectNodes.push(delay, wet, feedback);
        if (this.effect === 'web-chorus') { const lfo = ctx.createOscillator(), depth = ctx.createGain(); lfo.frequency.value = .7; depth.gain.value = .003; lfo.connect(depth); depth.connect(delay.delayTime); lfo.start(); this.effectNodes.push(lfo, depth); }
      } else if (this.effect === 'web-compressor') { const compressor = ctx.createDynamicsCompressor(); compressor.threshold.value = -18; compressor.ratio.value = 4; input.connect(compressor); compressor.connect(this.micOutput); this.effectNodes.push(compressor); }
      else input.connect(this.micOutput);
    }
    const limiter = ctx.createDynamicsCompressor(); limiter.threshold.value = -2; limiter.ratio.value = 20; limiter.attack.value = .003; limiter.release.value = .12;
    this.micOutput.connect(limiter); limiter.connect(ctx.destination); this.effectNodes.push(limiter);
  }
  async destroy() {
    this.closed = true; this.stopMicrophone(); this.source?.disconnect(); for (const source of this.extraSources.values()) source.disconnect(); this.extraSources.clear();
    this.nativeMusic?.port.postMessage({ type: 'dispose' }); this.nativeVoice?.port.postMessage({ type: 'dispose' }); this.nativeMusic?.disconnect(); this.nativeVoice?.disconnect(); this.musicGain?.disconnect();
    if (this.context && this.context.state !== 'closed') await this.context.close();
  }
}
