import createDsp from './vendor/feiniu-dsp.js';

// One native instance per node; no fetch, microphone access, or allocations in
// the realtime process callback. The main thread supplies the reviewed WASM.
class FeiniuDsp extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const settings = options.processorOptions || {};
    this.role = settings.role === 'voice' ? 'voice' : 'music';
    this.channels = settings.channels === 1 ? 1 : 2;
    this.ready = false;
    this.pending = null;
    this.disposed = false;
    this.port.onmessage = ({data}) => {
      if(!data || typeof data !== 'object' || this.disposed) return;
      if(data.type === 'dispose') { this.dispose(); return; }
      if(data.type === 'reset' && this.ready && this.role === 'music') { if(this.currentPitch) { this.module._music_pitch(this.handle,0); this.module._music_pitch(this.handle,this.currentPitch); } return; }
      if(data.type === 'configure') { this.pending = data; if(this.ready) this.configure(data); }
    };
    const entropy = settings.randomBytes;
    let entropyPosition = 0;
    const randomFill = view => {
      if(!entropy || entropy.BYTES_PER_ELEMENT !== 1 || !view || view.BYTES_PER_ELEMENT !== 1 ||
          typeof view.set !== 'function' || entropyPosition + view.byteLength > entropy.byteLength)
        throw new Error('安全随机源不可用或已耗尽');
      view.set(entropy.subarray(entropyPosition,entropyPosition+view.byteLength));
      entropy.fill(0,entropyPosition,entropyPosition+view.byteLength);
      entropyPosition += view.byteLength;
    };
    Promise.resolve().then(()=>this.disposed?null:createDsp({wasmBinary:settings.wasmBinary,locateFile:()=>'',randomFill,print:()=>{},printErr:()=>{}})).then(module => {
      if(this.disposed || !module) return;
      this.module = module;
      this.handle = this.role === 'voice' ? module._fx_create(sampleRate) : module._music_create(sampleRate, this.channels);
      this.input = module._malloc(512*2);
      this.output = module._malloc(512*2);
      this.parameters = module._malloc(18*4);
      if(!this.handle || !this.input || !this.output || !this.parameters) throw new Error('init');
      this.ready = true;
      if(this.pending) this.configure(this.pending);
      this.port.postMessage({type:'ready',role:this.role,pitch:this.role==='music'});
    }).catch(error => {
      if(this.disposed) return;
      this.dispose();
      this.port.postMessage({type:'error',message:'安卓音效内核暂不可用，请重新打开音效设置。',
        diagnostic:String(error?.name||'Error')+': '+String(error?.message||'initialization')});
    });
  }
  configure(data) {
    if(!this.ready || this.disposed || !this.module) return;
    const m = this.module;
    if(this.role === 'voice') {
      if(!Array.isArray(data.values) || data.values.length !== 18 || data.values.some(v=>!Number.isFinite(v))) return;
      m.HEAPF32.set(data.values,this.parameters/4);
      if(!m._fx_config(this.handle,data.enabled?1:0,this.parameters)) {
        this.port.postMessage({type:'error',message:'人声音效参数超出允许范围。'});
        return;
      }
    } else {
      const bands = Array.isArray(data.bands)&&data.bands.length===10 ? data.bands : Array(10).fill(0);
      if(bands.some(v=>!Number.isFinite(v)||v< -12||v>12))return;
      m.HEAPF32.set(bands,this.parameters/4);
      const preamp = Number(data.preamp)||0, pitch = Number(data.pitch)||0;
      if(!Number.isFinite(preamp) || preamp < -20 || preamp > 12 || !Number.isFinite(pitch) || pitch < -12 || pitch > 12) {
        this.port.postMessage({type:'error',message:'音乐音效参数超出允许范围。'});
        return;
      }
      m._music_config(this.handle,data.enabled?1:0,this.parameters,preamp);
      m._music_pitch(this.handle,pitch);this.currentPitch=pitch;
    }
    const latency = this.role === 'voice' ? m._fx_latency(this.handle) : m._music_latency(this.handle);
    this.port.postMessage({type:'configured',latencyFrames:latency,latencyMs:latency*1000/sampleRate});
  }
  process(inputs,outputs) {
    const input = inputs[0], output = outputs[0];
    if(this.disposed) return false;
    if(!output?.length) return true;
    const frames = output[0].length;
    if(!this.ready || !input?.length || frames > 256) {
      output.forEach((channel,index)=>{channel.fill(0);const source=input?.[Math.min(index,input.length-1)];if(source?.length)channel.set(source.subarray(0,channel.length));});
      return true;
    }
    try {
    const m = this.module, pcm = m.HEAP16, offset = this.input/2;
    for(let i=0;i<frames;i++)for(let c=0;c<this.channels;c++){
      const value = input[Math.min(c,input.length-1)][i]||0;
      pcm[offset+i*this.channels+c]=Math.max(-32768,Math.min(32767,Math.round(value*32768)));
    }
    let result=this.input;
    if(this.role==='voice') {m._fx_process(this.handle,this.input,frames,this.channels,this.output);result=this.output;}
    else m._music_process(this.handle,this.input,frames*this.channels);
    const data=m.HEAP16;
    const resultChannels=this.role==='voice'?2:this.channels;
    for(let c=0;c<output.length;c++)for(let i=0;i<frames;i++)
      output[c][i]=data[result/2+i*resultChannels+Math.min(c,resultChannels-1)]/32768;
    return true;
    } catch(error) {
      output.forEach(channel=>channel.fill(0));
      this.dispose();
      this.port.postMessage({type:'error',message:'原生音效处理中断，已请求恢复浏览器音频。'});
      return false;
    }
  }
  dispose() {
    if(this.disposed)return;
    this.disposed=true;this.ready=false;this.pending=null;
    if(this.module) {
      if(this.handle) (this.role==='voice'?this.module._fx_destroy:this.module._music_destroy)(this.handle);
      [this.input,this.output,this.parameters].forEach(p=>{if(p)this.module._free(p);});
    }
    this.handle=this.input=this.output=this.parameters=0;
  }
}
registerProcessor('feiniu-dsp',FeiniuDsp);
