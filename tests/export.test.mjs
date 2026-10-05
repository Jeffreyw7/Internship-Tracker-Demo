import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {createExportController} from '../src/domain/exportController.ts';
import {exportApi} from '../src/data/exportApi.ts';
import {authApi,request,refreshCsrf} from '../src/data/api.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
const job=(state='PENDING')=>({id:'owned-job',state,createdAt:'2026-10-05T00:00:00Z',expiresAt:'2026-10-06T00:00:00Z',failureCode:null,downloadPath:state==='READY'?'/api/v2/exports/owned-job/download':null});
const defer=()=>{let resolve;const promise=new Promise(done=>{resolve=done;});return {promise,resolve};};
function fixture(overrides={}) {
  const tasks=new Map(),calls=[];let key=0;
  const api={create:async()=>{calls.push('create');return job();},status:async()=>{calls.push('status');return job('READY');},cancel:async()=>{calls.push('cancel');return job('CANCELLED');},download:async()=>new Blob(['full CSV']),...overrides};
  const controller=createExportController(api,{schedule:(callback,delay)=>{assert.equal(delay,2000);const id=++key;tasks.set(id,callback);return id;},cancel:id=>tasks.delete(id)});
  return {controller,tasks,calls,async poll(){const entry=tasks.entries().next().value;assert.ok(entry);tasks.delete(entry[0]);await entry[1]();}};
}
test('export creates one full-account job and polls until ready without downloading list pages',async()=>{
  const {controller,calls,tasks,poll}=fixture();
  await controller.start();assert.equal(controller.getSnapshot().job.state,'PENDING');assert.equal(tasks.size,1);
  await controller.start();assert.deepEqual(calls,['create']);
  await poll();assert.equal(controller.getSnapshot().job.state,'READY');assert.equal(tasks.size,0);
  assert.equal(await (await controller.download()).text(),'full CSV');
});
test('hidden exports stop polling and terminal/failed states have actionable messages',async()=>{
  const {controller,tasks}=fixture({status:async()=>job('EXPIRED')});
  await controller.start();controller.setVisible(false);assert.equal(tasks.size,0);
  controller.setVisible(true);await new Promise(resolve=>setImmediate(resolve));
  assert.equal(controller.getSnapshot().job.state,'EXPIRED');assert.match(controller.getSnapshot().message,/expired.*new export/i);assert.equal(tasks.size,0);
});
test('account clear discards delayed creation, status and downloads without retrying writes',async()=>{
  for(const operation of ['create','status','download']) {
    const pending=defer();
    const {controller,tasks}=fixture({[operation]:()=>pending.promise});
    let work;
    if(operation==='create')work=controller.start();
    else {await controller.start();work=operation==='status'?controller.refresh():controller.download();}
    controller.clear();pending.resolve(operation==='download'?new Blob(['private CSV']):job('READY'));await work;
    assert.equal(controller.getSnapshot().job,null);assert.equal(tasks.size,0);
    if(operation==='download')assert.equal(await work,null);
  }
});
test('cancellation is explicit and failed writes are never replayed',async()=>{
  const {controller,calls,tasks}=fixture();await controller.start();await controller.cancel();
  assert.deepEqual(calls,['create','cancel']);assert.equal(tasks.size,0);assert.equal(controller.getSnapshot().job.state,'CANCELLED');
  let writes=0;const failed=fixture({create:async()=>{writes++;throw new Error('Try again later');}});
  await failed.controller.start();assert.equal(writes,1);assert.match(failed.controller.getSnapshot().error,/Try again/);
});
test('export adapters use session cookies, CSRF for writes and binary CSV transport',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options)=>{
    calls.push({url,options});
    if(url==='/api/auth/csrf')return new Response(JSON.stringify({token:'synthetic-token',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}});
    if(url.endsWith('/download'))return new Response('\uFEFFfull CSV',{headers:{'Content-Type':'text/csv'}});
    return new Response(JSON.stringify(job(url.includes('/owned-job')?'CANCELLED':'PENDING')),{headers:{'Content-Type':'application/json'}});
  };
  await refreshCsrf();await exportApi.create();await exportApi.cancel('owned-job');const blob=await exportApi.download('owned-job');
  assert.equal(await blob.text(),'full CSV');
  const writes=calls.filter(call=>['POST','DELETE'].includes(call.options.method));
  assert.equal(writes.length,2);for(const call of writes)assert.equal(new Headers(call.options.headers).get('X-CSRF-TOKEN'),'synthetic-token');
  const download=calls.at(-1);assert.equal(download.url,'/api/v2/exports/owned-job/download');assert.equal(download.options.credentials,'same-origin');assert.equal(download.options.cache,'no-store');
});
test('an export waiting for CSRF cannot create a job under a later login',async()=>{
  const delayed=defer();let delay=false,writes=0;
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return delay?delayed.promise:new Response(JSON.stringify({token:'current',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}});
    if(url==='/api/auth/logout')return new Response(null,{status:204});
    if(url==='/api/auth/login')return new Response(JSON.stringify({id:'new-account'}),{headers:{'Content-Type':'application/json'}});
    writes++;return new Response(JSON.stringify(job()),{headers:{'Content-Type':'application/json'}});
  };
  await authApi.logout();delay=true;
  const pending=request('/v2/exports',{method:'POST'});const rejected=assert.rejects(pending);
  await new Promise(resolve=>setImmediate(resolve));delay=false;await authApi.login('new@example.test','synthetic');
  delayed.resolve(new Response(JSON.stringify({token:'old',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}}));
  await rejected;assert.equal(writes,0);
});
