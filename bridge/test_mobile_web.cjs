// Dependency-free checks for phone WebView routing and credential handling.
// Run from any directory: node bridge/test_mobile_web.cjs
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
  constructor(tag='div') { this.tagName=tag; this.value=''; this.textContent=''; this.children=[]; this.hidden=false; this.disabled=false; this.checked=false; this.dataset={}; this.attributes={}; this.classList={toggle(){},add(){},remove(){}}; }
  set innerHTML(_) { throw Error('Untrusted HTML insertion is forbidden'); }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children=[...nodes]; }
  setAttribute(key,value) { this.attributes[key]=String(value); }
  removeAttribute(key) { delete this.attributes[key]; }
  addEventListener() {}
  querySelectorAll() { return []; }
  focus() {}
  remove() {}
}
function context(file,pathname) {
  const source=fs.readFileSync(path.join(__dirname,file),'utf8');
  const script=source.match(/<script>([\s\S]*?)<\/script>/)[1];
  new vm.Script(script,{filename:file});
  const nodes=new Map(),requests=[],sockets=[];
  const document={getElementById(id){if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);},createElement(tag){return new Element(tag);},querySelectorAll(){return [];},addEventListener(){}};
  class Socket { static OPEN=1; constructor(url,protocols=[]){this.url=url;this.protocols=protocols;this.readyState=1;sockets.push(this);}close(){this.readyState=3;}send(value){this.sent=value;} }
  const sandbox=vm.createContext({document,location:{pathname,origin:'https://nas.example',protocol:'https:'},URL,URLSearchParams,AbortController,WebSocket:Socket,window:{addEventListener(){}},setTimeout(){return 1;},clearTimeout(){},console,fetch:async(url,options)=>{requests.push({url,options});return {ok:true,json:async()=>url.endsWith('/api/tv-auth/session')?{authed:true,gatewayProof:'synthetic-gateway-proof',gatewayProofExpiresAt:Math.floor(Date.now()/1000)+120}:{}};}});
  vm.runInContext(script.replace(/\nstart\(\);\s*$/,''),sandbox);
  return {run:code=>vm.runInContext(code,sandbox),nodes,requests,sockets,sandbox};
}
async function main(){
  for(const file of ['mobile.html','sources.html']) {
    for(const pathname of ['/','/m','/sources','/app/feiniu-karaoke','/app/feiniu-karaoke/','/app/feiniu-karaoke/m','/app/feiniu-karaoke/sources']) {
      const c=context(file,pathname),prefix=pathname.startsWith('/app/')?'/app/feiniu-karaoke':'';
      assert.equal(c.run("localUrl('/api/songs')"),prefix+'/api/songs');
      assert.equal(c.run("localUrl(localUrl('/api/songs'))"),prefix+'/api/songs','prefix is idempotent');
      await c.run("api('/api/queue',{song_id:8})");
      assert.equal(c.requests.at(-1).url,prefix+'/api/queue');
      assert.equal(c.requests.at(-1).options.credentials,'same-origin');
      assert.equal(c.requests.at(-1).options.method,'POST');
      if(prefix){
        assert.equal(c.requests[0].url,prefix+'/api/tv-auth/session','refresh proof before the first gateway mutation');
        assert.equal(c.requests[0].options.headers['X-Karaoke-Origin'],'https://nas.example');
        assert.equal(c.requests.at(-1).options.headers['X-Karaoke-Proof'],'synthetic-gateway-proof');
      }else assert.equal(c.requests.length,1,'direct TCP requests need no gateway proof');
      if(file==='mobile.html'){
        c.run("state.authed=true;connect()");
        assert.equal(c.sockets[0].url,'wss://nas.example'+prefix+'/ws');
        assert.deepEqual(Array.from(c.sockets[0].protocols),prefix?['feiniu-karaoke','fnos-proof.synthetic-gateway-proof']:[]);
        assert.ok(!c.sockets[0].url.includes('proof'),'proof is never put into a URL');
        c.run("state.total=1;render([{id:8,title:'<img src=x onerror=alert(1)>',artist:'<script>bad</script>',media_kind:'mv'}])");
        const row=c.nodes.get('songs').children[0];
        assert.equal(row.children[0].children[0].src,prefix+'/api/cover/8');
        assert.equal(row.children[1].children[0].textContent,'<img src=x onerror=alert(1)>');
        c.run("setQueue([{song_id:8,queue_id:19,title:'Test',status:'playing'}]);state.mode='queue';render(state.queue)");
        assert.equal(c.nodes.get('pagination').hidden,true,'queue never pretends to paginate');
        const remove=c.nodes.get('songs').children[0].children[2];
        await remove.onclick();
        const deletion=c.requests.find(request=>request.options.method==='DELETE');
        assert.equal(deletion.url,prefix+'/api/queue/19');
      }
    }
  }
  for(const [file,form,password] of [['mobile.html','login','pass'],['sources.html','auth','password']]){
    const c=context(file,'/app/feiniu-karaoke');
    c.run("$('"+password+"').value='temporary-test-value'");
    c.sandbox.fetch=async()=>{assert.equal(c.nodes.get(password).value,'','password cleared before network request');throw Error('offline');};
    await c.nodes.get(form).onsubmit({preventDefault(){}});
    assert.equal(c.nodes.get(password).value,'');
  }
  const sources=context('sources.html','/app/feiniu-karaoke/sources');
  sources.run("admin=true");
  sources.run("$('musicPassword').value='temporary-test-value'");
  sources.sandbox.fetch=async()=>{assert.equal(sources.nodes.get('musicPassword').value,'');throw Error('offline');};
  await sources.nodes.get('musicForm').onsubmit({preventDefault(){}});
  sources.run("gatewayUser=true;setAdmin(false)");
  assert.equal(sources.nodes.get('auth').hidden,true,'trusted NAS ordinary user does not see service-login prompt');
  sources.run("showKugou({status:'not_configured'})");
  assert.equal(sources.nodes.get('kugouLogin').disabled,true,'optional unavailable provider is not actionable');
  const scoring=context('sources.html','/app/feiniu-karaoke/sources');
  scoring.run("showScores({generationEnabled:true,workerAvailable:true,paused:false,active:[]});setAdmin(false)");
  assert.equal(scoring.nodes.get('scorePause').disabled,true);
  await scoring.run("scoreAction('pause')");
  assert.equal(scoring.requests.length,0,'ordinary user cannot issue a scoring mutation');
  scoring.run("setAdmin(true)");
  assert.equal(scoring.nodes.get('scorePause').disabled,false);
  assert.equal(scoring.nodes.get('scoreResume').disabled,true);
  await scoring.run("scoreAction('cancel',23)");
  assert.equal(scoring.requests[0].url,'/app/feiniu-karaoke/api/tv-auth/session');
  const cancellation=scoring.requests.find(request=>request.url.endsWith('/api/admin/scoring/cancel'));
  assert.ok(cancellation,'cancellation is sent after the proof refresh resolves');
  assert.equal(cancellation.options.method,'POST');
  assert.equal(cancellation.options.body,'{"songId":23}');
  assert.equal(cancellation.options.headers['X-Karaoke-Proof'],'synthetic-gateway-proof');
  scoring.run("showScores({generationEnabled:false,workerAvailable:false,active:[]})");
  assert.equal(scoring.nodes.get('scoreControls').hidden,true,'cache-only installation offers no generation switch');
  for(const file of ['mobile.html','sources.html']){
    const c=context(file,'/app/feiniu-karaoke/m');
    await Promise.all([c.run("api('/api/admin/scoring/pause',{})"),c.run("api('/api/admin/scoring/resume',{})")]);
    assert.equal(c.requests.filter(request=>request.url.endsWith('/api/tv-auth/session')).length,1,'concurrent mutations share one proof refresh');
    assert.equal(c.requests.filter(request=>request.options.method==='POST').length,2);
    for(const mutation of c.requests.filter(request=>request.options.method==='POST')){
      assert.equal(mutation.options.headers['X-Karaoke-Proof'],'synthetic-gateway-proof');
      assert.ok(!mutation.url.includes('proof'));
    }
    c.run('gatewayProofUntil=0');
    await c.run("api('/api/admin/scoring/pause',{})");
    assert.equal(c.requests.filter(request=>request.url.endsWith('/api/tv-auth/session')).length,2,'expired proof is refreshed before the next mutation');
  }
  const loading=context('mobile.html','/app/feiniu-karaoke');
  const reads=[];
  loading.sandbox.fetch=(url,options)=>new Promise((resolve,reject)=>{
    reads.push({url,options,resolve});
    options.signal.addEventListener('abort',()=>{const error=new Error('cancelled');error.name='AbortError';reject(error);});
  });
  const firstLoad=loading.run('load()');
  await loading.run('load()');
  assert.equal(reads.length,1,'same pending page is requested only once');
  const nextLoad=loading.run("state.kind='mv';load()");
  assert.equal(reads[0].options.signal.aborted,true,'changing filter cancels the obsolete request');
  reads[1].resolve({ok:true,json:async()=>({items:[],total:0})});
  await Promise.all([firstLoad,nextLoad]);
  assert.equal(loading.nodes.get('emptyTitle').textContent,'没有找到匹配的歌曲');
  console.log('Phone UI checks passed: 14 routing cases, same-origin API/WS/covers, safe text rendering, queue removal, credential clearing, gateway permissions, optional provider and scoring control gates.');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
