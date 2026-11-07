import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {registerHooks} from 'node:module';
import ts from 'typescript';
import {JSDOM} from 'jsdom';

registerHooks({
  resolve(specifier,context,next) {
    try {return next(specifier,context);}
    catch(error) {
      if(specifier.startsWith('.')&&context.parentURL)for(const extension of ['.ts','.tsx']) {
        const url=new URL(specifier+extension,context.parentURL);
        if(fs.existsSync(fileURLToPath(url)))return next(url.href,context);
      }
      throw error;
    }
  },
  load(url,context,next) {
    if(url.startsWith('file:')&&/\/src\/.*\.tsx?$/.test(url))return {format:'module',shortCircuit:true,
      source:ts.transpileModule(fs.readFileSync(fileURLToPath(url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText};
    return next(url,context);
  },
});
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost/'});
for(const key of ['window','document','HTMLElement','HTMLDialogElement','HTMLInputElement','Event'])globalThis[key]=dom.window[key];
globalThis.sessionStorage=dom.window.sessionStorage;
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
HTMLElement.prototype.scrollIntoView=function(){};
window.scrollTo=function(){};
window.matchMedia=query=>Object.assign(new window.EventTarget(),{media:query,matches:false});
const {createElement,act}=await import('react');
const {createRoot}=await import('react-dom/client');
const {useApplications}=await import('../src/hooks/useApplications.ts');
const {default:App}=await import('../src/App.tsx');
const {ThemeProvider}=await import('../src/theme/ThemeProvider.tsx');
const owner='10000000-0000-0000-0000-000000000001';
const draft={company:'Acme',role:'Intern',location:'Remote',status:'Saved',appliedDate:'',deadline:'',followUpDate:'',notes:'Private draft',stages:[]};
const saved=(id='20000000-0000-0000-0000-000000000002',patch={})=>({...draft,id,version:0,createdAt:'2026-11-07T12:00:00Z',updatedAt:'2026-11-07T12:00:00Z',sample:false,deleted:false,...patch});
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const history=content=>json({content,nextCursor:null,totalElements:content.length,totalPages:content.length?1:0,page:0,size:100});
const realFetch=globalThis.fetch;
let root,container,hook;
async function mount(account=owner) {
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  function Harness(){hook=useApplications(account);return null;}
  await act(async()=>root.render(createElement(Harness)));
}
async function unmount(){if(root)await act(async()=>root.unmount());root=null;container?.remove();}
afterEach(async()=>{await unmount();sessionStorage.clear();globalThis.fetch=realFetch;});

test('a lost create response followed by an explicit retry saves one record',async()=>{
  const records=[],keys=[];let loseResponse=true;
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method!=='POST')return history(records);
    const key=options.headers.get('Idempotency-Key');keys.push(key);
    let result=key&&records.find(r=>r.key===key)?.application;
    if(!result){result=saved(crypto.randomUUID());records.push({key,application:result});}
    if(loseResponse){loseResponse=false;throw new TypeError('Response lost after commit');}
    return json(result,201);
  };
  await mount();
  await act(async()=>assert.rejects(hook.save(draft)));
  await act(async()=>hook.save(draft));
  assert.equal(records.length,1,'retry must reuse the original create identity');
  assert.ok(keys[0]);assert.equal(keys[1],keys[0]);
  assert.equal(hook.apps.length,1);
});

test('an unconfirmed create survives remount and recovers by GET without another POST',async()=>{
  let posts=0,key;const record=saved();
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/application-creates/'))return json({status:'COMPLETED',application:record});
    if(options.method==='POST'){posts++;key=options.headers.get('Idempotency-Key');throw new TypeError('Lost result');}
    return history(posts?[record]:[]);
  };
  await mount();await act(async()=>assert.rejects(hook.save(draft)));
  const stored=sessionStorage.getItem(`campus:create:${owner}`);
  assert.ok(stored.includes(key));assert.ok(!stored.includes(draft.notes));assert.ok(!stored.includes(draft.company));
  await unmount();await mount();
  assert.equal(posts,1);assert.equal(hook.pendingCreate,null);assert.equal(hook.ready,true);
  assert.deepEqual(hook.apps,[record]);assert.equal(sessionStorage.getItem(`campus:create:${owner}`),null);
});

test('uncertain creates block changed drafts and unrelated writes until explicitly cancelled',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options={})=>{
    calls.push({url,method:options.method});
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/application-creates/'))return options.method==='DELETE'?json({status:'CANCELLED',application:null}):json({code:'CREATE_NOT_FOUND',message:'Unknown'},404);
    if(options.method==='POST')throw new TypeError('Connection lost');
    return history([]);
  };
  await mount();await act(async()=>assert.rejects(hook.save(draft)));
  await act(async()=>assert.rejects(hook.save({...draft,notes:'Different'}),/unconfirmed/));
  await act(async()=>assert.rejects(hook.remove(saved()),/pending save/));
  await act(async()=>hook.checkCreate());
  assert.ok(hook.pendingCreate);assert.equal(calls.filter(c=>c.method==='POST').length,1);
  await act(async()=>hook.cancelCreate());
  assert.equal(hook.pendingCreate,null);assert.equal(hook.busy,false);
});

test('cancelling a save that already committed recovers its current deleted state',async()=>{
  const deleted=saved(undefined,{deleted:true,version:3});
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/application-creates/'))return json({status:'COMPLETED',application:deleted});
    if(options.method==='POST')throw new TypeError('Lost result');
    return history([]);
  };
  await mount();await act(async()=>assert.rejects(hook.save(draft)));
  let outcome;await act(async()=>{outcome=await hook.cancelCreate();});
  assert.equal(outcome.application.deleted,true);assert.deepEqual(hook.apps,[]);assert.equal(hook.pendingCreate,null);
});

test('an unknown result remains locked after an explicit retry rejection',async()=>{
  let posts=0;
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method==='POST'){if(++posts===1)throw new TypeError('Lost response');return json({message:'Rate limited'},429);}
    return history([]);
  };
  await mount();await act(async()=>assert.rejects(hook.save(draft)));
  const key=hook.pendingCreate.key;
  await act(async()=>assert.rejects(hook.save(draft)));
  assert.equal(hook.pendingCreate.key,key);assert.equal(hook.busy,true);assert.equal(posts,2);
});

test('malformed successful create responses keep the recovery reference',async()=>{
  globalThis.fetch=async(url,options={})=>url==='/api/auth/csrf'?json({token:'test',headerName:'X-CSRF-TOKEN'}):options.method==='POST'?json({id:'incomplete'},201):history([]);
  await mount();await act(async()=>assert.rejects(hook.save(draft),/incomplete/));
  assert.ok(hook.pendingCreate);assert.deepEqual(hook.apps,[]);
});

test('a pending create in one account cannot lock another account or receive its late response',async()=>{
  let finish,dispatched;const started=new Promise(resolve=>{dispatched=resolve;});
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method==='POST')return new Promise(resolve=>{finish=resolve;dispatched();});
    return history([]);
  };
  await mount();let request;
  await act(async()=>{request=hook.save(draft);await started;});
  const rejected=assert.rejects(request,/earlier workspace/);
  await unmount();await mount('30000000-0000-0000-0000-000000000003');
  await act(async()=>{finish(json(saved(),201));await rejected;});
  assert.equal(hook.pendingCreate,null);assert.equal(hook.busy,false);assert.deepEqual(hook.apps,[]);
  assert.ok(sessionStorage.getItem(`campus:create:${owner}`));
});

test('unavailable tab storage prevents a create from dispatching without a recovery reference',async()=>{
  let posts=0;
  globalThis.fetch=async(url,options={})=>{if(options.method==='POST')posts++;return history([]);};
  await mount();const storage=globalThis.sessionStorage;
  globalThis.sessionStorage={getItem:()=>null,setItem:()=>{throw new Error('Blocked');}};
  try {await act(async()=>assert.rejects(hook.save(draft),/Tab storage is unavailable/));}
  finally {globalThis.sessionStorage=storage;}
  assert.equal(posts,0);assert.equal(hook.pendingCreate,null);
});

test('a first server validation rejection leaves the draft editable without a pending operation',async()=>{
  globalThis.fetch=async(url,options={})=>url==='/api/auth/csrf'?json({token:'test',headerName:'X-CSRF-TOKEN'}):options.method==='POST'?json({message:'Invalid date'},400):history([]);
  await mount();await act(async()=>assert.rejects(hook.save(draft),/Invalid date/));
  assert.equal(hook.pendingCreate,null);assert.equal(hook.busy,false);
});

test('conflict reload updates the shared record and rejects a malformed latest response',async()=>{
  const latest=saved(undefined,{version:2,notes:'Remote update'});let malformed=false;
  globalThis.fetch=async(url)=>url.startsWith('/api/applications/')&&!url.includes('/history')?json(malformed?{id:latest.id}:latest):history([saved()]);
  await mount();let found;
  await act(async()=>{found=await hook.getLatest(latest.id);});
  assert.deepEqual(found,latest);assert.deepEqual(hook.apps,[latest]);
  malformed=true;
  await act(async()=>assert.rejects(hook.getLatest(latest.id),/invalid application/));
  assert.deepEqual(hook.apps,[latest]);
});

test('a late conflict read cannot replace a newer confirmed mutation in the workspace',async()=>{
  let finishRead;const latest=saved(undefined,{version:3,notes:'Confirmed newer save'});
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method==='PUT')return json(latest);
    if(url===`/api/applications/${latest.id}`)return new Promise(resolve=>{finishRead=resolve;});
    return history([saved()]);
  };
  await mount();let reading;
  await act(async()=>{reading=hook.getLatest(latest.id);});
  await act(async()=>hook.save({...draft,notes:latest.notes},latest.id,2));
  await act(async()=>{finishRead(json(saved(undefined,{version:2,notes:'Old read'})));await reading;});
  assert.deepEqual(hook.apps,[latest]);
});

test('conflict recovery can restore failed history loading without closing the draft',async()=>{
  let histories=0,puts=0;const latest=saved(undefined,{version:1,location:'New location'});
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method==='PUT')return ++puts===1?json({message:'Changed elsewhere'},409):json({...latest,version:2,notes:'Local edit'});
    if(url===`/api/applications/${latest.id}`)return json(latest);
    if(++histories===2)throw new TypeError('Temporary history failure');
    return history([latest]);
  };
  await mount();await act(async()=>assert.rejects(hook.save(draft,latest.id,0)));
  assert.equal(hook.ready,false);
  await act(async()=>hook.getLatest(latest.id));
  await act(async()=>hook.save({...draft,location:latest.location,notes:'Local edit'},latest.id,1));
  assert.equal(puts,2);assert.equal(hook.ready,true);assert.equal(hook.apps[0].notes,'Local edit');
});

test('a late missing-record response cannot remove a newly restored application',async()=>{
  let finishRead;const restored=saved(undefined,{version:3});
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(options.method==='POST')return json(restored);
    if(url===`/api/applications/${restored.id}`)return new Promise(resolve=>{finishRead=resolve;});
    return history([]);
  };
  await mount();let reading;
  await act(async()=>{reading=hook.getLatest(restored.id);});
  await act(async()=>hook.restoreDeleted(saved(undefined,{version:2,deleted:true})));
  await act(async()=>{finishRead(json({message:'Not found'},404));await reading;});
  assert.deepEqual(hook.apps,[restored]);
});

test('cancelling an unconfirmed new save keeps the draft open and editable',async()=>{
  let posted=false;
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return json({token:'test',headerName:'X-CSRF-TOKEN'});
    if(url==='/api/me/preferences')return json({themePreference:'system'});
    if(url.startsWith('/api/application-creates/'))return json({status:'CANCELLED',application:null});
    if(options.method==='POST'){posted=true;throw new TypeError('Lost response');}
    if(posted)throw new TypeError('History currently unavailable');
    return history([]);
  };
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(ThemeProvider,null,createElement(App,{user:{id:owner,displayName:'Tester',email:'tester@example.test'},onLogout:async()=>{}}))));
  await act(async()=>container.querySelector('.add-main').click());
  for(const [selector,value] of [['input[placeholder="e.g. Figma"]','Acme'],['input[placeholder="e.g. Software Engineer Intern"]','Intern']]) {
    await act(async()=>{const field=container.querySelector(selector);Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(field,value);field.dispatchEvent(new Event('input',{bubbles:true}));});
  }
  await act(async()=>container.querySelector('dialog form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  await act(async()=>[...container.querySelectorAll('dialog button')].find(button=>button.textContent==='Cancel pending save').click());
  assert.ok(container.querySelector('dialog'),'cancel the uncertain request without discarding its draft');
  assert.equal(container.querySelector('input[placeholder="e.g. Figma"]').value,'Acme');
  assert.equal(container.querySelector('dialog .drawer-body').hasAttribute('inert'),false);
  assert.equal(container.querySelector('.add-main').disabled,false,'cancellation must not require another successful history read');
});
