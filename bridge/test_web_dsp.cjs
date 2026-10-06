const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

async function main() {
  const root=path.resolve(__dirname,'../artifacts/fnos/web-dsp');
  const {default:createDsp}=await import(pathToFileURL(path.join(root,'feiniu-dsp.js')));
  const native=await createDsp({wasmBinary:fs.readFileSync(path.join(root,'feiniu-dsp.wasm')),randomFill:view=>require('node:crypto').randomFillSync(view),print:()=>{},printErr:()=>{}});
  const frames=128, rate=48000, input=native._malloc(frames*4),output=native._malloc(frames*4),values=native._malloc(18*4);
  const fx=native._fx_create(rate),music=native._music_create(rate,2);
  assert(fx&&music);
  assert.equal(native._fx_create(0),0);
  const defaults=[0,0,0,1.2,0,160,0,50,0,0,0,0,0,0,0,0,0,0];
  native.HEAPF32.set(defaults,values/4);
  assert.equal(native._fx_config(fx,0,values),1);
  const original=Int16Array.from({length:frames*2},(_,i)=>Math.round(12000*Math.sin(i/2*2*Math.PI*440/rate)));
  native.HEAP16.set(original,input/2);
  native._fx_process(fx,input,frames,2,output);
  assert.deepEqual(Array.from(native.HEAP16.slice(output/2,output/2+frames*2)),Array.from(original),'original vocal passes exact PCM');
  const invalid=[...defaults];invalid[0]=99;
  native.HEAPF32.set(invalid,values/4);
  assert.equal(native._fx_config(fx,1,values),0,'native rejects invalid parameters');
  native.HEAPF32.set(Array(10).fill(0),values/4);
  native._music_config(music,0,values,0);
  native.HEAP16.set(original,input/2);
  native._music_process(music,input,frames*2);
  assert.deepEqual(Array.from(native.HEAP16.slice(input/2,input/2+frames*2)),Array.from(original),'disabled EQ preserves PCM');
  native._music_pitch(music,12);
  const rendered=[];
  for(let block=0;block<750;block++) {
    for(let i=0;i<frames;i++)native.HEAP16[input/2+i*2]=native.HEAP16[input/2+i*2+1]=Math.round(10000*Math.sin((block*frames+i)*2*Math.PI*440/rate));
    native._music_process(music,input,frames*2);
    if(block>=400)for(let i=0;i<frames;i++)rendered.push(native.HEAP16[input/2+i*2]);
  }
  let crossings=0;for(let i=1;i<rendered.length;i++)if(rendered[i-1]<=0&&rendered[i]>0)crossings++;
  const hz=crossings/(rendered.length/rate);
  assert(hz>860&&hz<900,`+12 semitones changes 440Hz into 880Hz, got ${hz}`);
  assert(native._music_latency(music)>0);
  const cartoon=[...defaults];cartoon[0]=7;cartoon[1]=5;cartoon[2]=8;cartoon[9]=2;
  native.HEAPF32.set(cartoon,values/4);
  assert.equal(native._fx_config(fx,1,values),1);
  const start=performance.now();let audible=0;
  for(let block=0;block<750;block++){
    native.HEAP16.set(original,input/2);native._fx_process(fx,input,frames,2,output);
    for(const value of native.HEAP16.slice(output/2,output/2+frames*2)){assert(Math.abs(value)<=29492);audible+=Math.abs(value);}
  }
  assert(audible>100000,'native vocal effect produces non-silent bounded output');
  const elapsed=performance.now()-start;
  native._fx_destroy(fx);native._music_destroy(music);[input,output,values].forEach(p=>native._free(p));
  console.log(JSON.stringify({checks:7,pitchHz:hz,nativeVoiceMsPer128Frames:elapsed/750}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
