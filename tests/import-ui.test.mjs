// Exercise the real React dialog in a DOM. Native browser rendering/tab containment still require browser QA.
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
for(const key of ['window','document','HTMLElement','HTMLDialogElement','HTMLInputElement','HTMLTextAreaElement','MouseEvent','Event','CustomEvent'])globalThis[key]=dom.window[key];
globalThis.sessionStorage=dom.window.sessionStorage;
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
// JSDOM has no native modal rendering. These shims only allow React's effects to execute.
dom.window.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
dom.window.HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
dom.window.HTMLElement.prototype.scrollIntoView=function(){};
const {createElement,act,StrictMode,useState}=await import('react');
const {createRoot}=await import('react-dom/client');
const {ApplicationImportDialog}=await import('../src/components/ApplicationImportDialog.tsx');
const {useApplications}=await import('../src/hooks/useApplications.ts');
const originalFetch=globalThis.fetch;
const id='10000000-0000-0000-0000-000000000001',targetId='20000000-0000-0000-0000-000000000002';
const record={company:'Acme',role:'Intern',location:'',status:'Saved',appliedDate:null,deadline:null,followUpDate:null,notes:'old',stages:[]};
const row=(number=1)=>({rowNumber:number,cells:[record.company,record.role],action:'Update',targetId,targetVersion:0,
  targetSummary:{id:targetId,company:'Acme',role:'Intern',location:''},candidates:[],moreCandidates:false,
  intents:{notes:{intent:'SET',value:'new'}},before:record,after:{...record,notes:'new'},
  differences:[{field:'notes',before:'old',after:'new'}],issues:[],warnings:[]});
const preview=(patch={})=>({id,schemaVersion:1,policyVersion:'csv-import-1',createdAt:'2026-10-15T12:00:00Z',expiresAt:'2026-10-15T12:30:00Z',
  reviewDigest:'digest',committable:true,headers:['Company','Role'],rows:[row()],counts:{added:0,updated:1,skipped:0,unchanged:0,errors:0},issues:[],...patch});
let root,container;
async function render(props={}) {
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(ApplicationImportDialog,{onClose(){},commit:async()=>{throw Error('Unexpected write');},
    recoveryPreview:preview(),requestBusy:false,receipt:null,refreshFailed:false,checkStatus:async()=>{},refresh:async()=>{},...props})));
}
const button=text=>[...container.querySelectorAll('button')].find(b=>b.textContent.includes(text));
const click=async element=>act(async()=>{element.click();});
const change=async(element,value)=>act(async()=>{element.value=value;element.dispatchEvent(new Event('change',{bubbles:true}));});
afterEach(async()=>{if(root)await act(async()=>root.unmount());root=undefined;container?.remove();document.body.replaceChildren();sessionStorage.clear();globalThis.fetch=originalFetch;});

test('update confirmation states the count, requires a separate acknowledgement, and submits only after it',async()=>{
  let writes=0;
  const receipt={importId:id,receiptId:targetId,completedAt:'2026-10-15T12:05:00Z',retainUntil:'2026-11-14T12:05:00Z',counts:preview().counts,rows:[]};
  await render({pendingId:id,commit:async(p,retry)=>{writes++;assert.equal(p.id,id);assert.equal(retry,true);return receipt;}});
  await click(button('Review final confirmation'));
  const submit=button('Import 0 new applications and update 1 existing applications');
  assert.equal(submit.disabled,true);
  const acknowledgement=[...container.querySelectorAll('label')].find(l=>l.textContent.includes('I understand that 1 existing applications will be updated'));
  assert.ok(acknowledgement);
  await click(acknowledgement.querySelector('input'));
  assert.equal(submit.disabled,false);assert.equal(writes,0);
  await click(submit);assert.equal(writes,1);
  assert.ok(container.textContent.includes('Import completed'));
});

test('uploaded markup remains text, row errors describe their controls, and closing restores prior focus',async()=>{
  const prior=document.createElement('button');prior.textContent='Import CSV';document.body.append(prior);prior.focus();
  const bad={...row(),cells:['<img src=x onerror="alert(1)">','Intern'],issues:[{code:'INVALID',field:'notes',message:'Check notes.'}]};
  await render({recoveryPreview:preview({rows:[bad],committable:false,counts:{...preview().counts,errors:1}})});
  assert.equal(container.querySelector('img'),null);
  const notes=container.querySelector('#import-row-1-notes');
  assert.equal(notes.getAttribute('aria-invalid'),'true');
  assert.ok(document.getElementById(notes.getAttribute('aria-describedby')).textContent.includes('Check notes.'));
  assert.ok(container.contains(document.activeElement));
  await act(async()=>root.unmount());root=undefined;
  assert.equal(document.activeElement,prior);
});

test('next-error navigation crosses review pages and focuses the invalid field',async()=>{
  const rows=Array.from({length:11},(_,index)=>({...row(index+1),targetId:null,targetVersion:null,targetSummary:null,action:'Add'}));
  rows[10].issues=[{code:'INVALID',field:'notes',message:'Check notes.'}];
  await render({recoveryPreview:preview({rows,committable:false,counts:{added:11,updated:0,skipped:0,unchanged:0,errors:1}})});
  await click(button('Go to next row with errors'));
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,10));});
  assert.ok(container.textContent.includes('Page 2 of 2'));
  assert.equal(document.activeElement?.id,'import-row-11-notes');
});

test('file mapping changes require a revised server preview before confirmation',async()=>{
  const requests=[];let previews=0;
  globalThis.fetch=async(url,options)=>{
    if(url==='/api/auth/csrf')return new Response(JSON.stringify({token:'token',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}});
    assert.equal(url,'/api/application-imports/preview');requests.push(JSON.parse(await options.body.get('options').text()));
    previews++;
    return new Response(JSON.stringify(preview({id:previews===1?id:targetId,rows:[{...row(),action:'Add',targetId:null,targetVersion:null,targetSummary:null}],
      committable:previews>1,counts:{added:1,updated:0,skipped:0,unchanged:0,errors:0},issues:previews===1?[{code:'MAPPING',field:'mapping',message:'Map all columns.'}]:[]})),{status:201,headers:{'Content-Type':'application/json'}});
  };
  await render({recoveryPreview:null});
  const file=container.querySelector('#import-file');
  Object.defineProperty(file,'files',{value:[new File(['Company,Role\nAcme,Intern\n'],'test.csv',{type:'text/csv'})]});
  await act(async()=>file.dispatchEvent(new Event('change',{bubbles:true})));
  await click(button('Read file and show columns'));
  assert.equal(previews,1);
  await change(container.querySelector('#import-map-0'),'company');
  await change(container.querySelector('#import-map-1'),'role');
  assert.equal(button('Review final confirmation').disabled,true);
  assert.equal(previews,1,'Changing a mapping cannot automatically dispatch a write.');
  await click(button('Create revised preview'));
  assert.equal(previews,2);
  assert.deepEqual(requests[1].mapping,{'0':'company','1':'role'});
  assert.equal(requests[1].supersedesPreviewId,id);
  assert.equal(button('Review final confirmation').disabled,false);
});

const response=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const application=(company='Acme')=>({...record,id:targetId,company,appliedDate:'',deadline:'',followUpDate:'',version:0,
  createdAt:'2026-10-15T12:00:00Z',updatedAt:'2026-10-15T12:00:00Z',sample:false,deleted:false});
let hook;
function Probe({owner}){hook=useApplications(owner);return null;}
async function mountProbe(owner=id){
  if(!root){container=document.createElement('div');document.body.append(container);root=createRoot(container);}
  await act(async()=>root.render(createElement(StrictMode,null,createElement(Probe,{owner,key:owner}))));
}

test('unknown commit holds the hook write lock while a read refresh recovers and GET returns the receipt',async()=>{
  let failHistory=false,complete=false,writes=0,historyReads=0;
  const receipt={importId:id,receiptId:targetId,completedAt:'2026-10-15T12:05:00Z',retainUntil:'2026-11-14T12:05:00Z',counts:preview().counts,
    rows:[{rowNumber:1,action:'Update',applicationId:targetId,resultVersion:1}]};
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return response({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/applications?')){historyReads++;return failHistory?response({message:'Read failed'},503):response({content:[application()],totalPages:1});}
    if(url.endsWith('/commit')){writes++;failHistory=true;return new Response('{',{headers:{'Content-Type':'application/json'}});}
    if(url===`/api/application-imports/${id}`)return response(complete?{status:'COMPLETED',receipt}:{status:'PREVIEW',preview:preview()});
    throw Error('Unexpected request '+url+' '+options.method);
  };
  await mountProbe();assert.equal(hook.ready,true);
  await act(async()=>assert.rejects(hook.commitImport(preview()),/not yet confirmed/));
  assert.equal(writes,1);assert.equal(hook.pendingImport.previewId,id);assert.equal(hook.busy,true);
  await assert.rejects(hook.save({...record,appliedDate:'',deadline:'',followUpDate:''}),/pending import/);
  await act(async()=>{await hook.refresh();});assert.equal(hook.ready,false);assert.equal(hook.pendingImport.previewId,id);
  failHistory=false;
  await act(async()=>{await hook.refresh();});assert.equal(hook.ready,true);assert.equal(hook.pendingImport.previewId,id);
  complete=true;
  await act(async()=>{await hook.reconcileImport();});
  assert.equal(hook.pendingImport,null);assert.equal(hook.importReceipt.receiptId,targetId);assert.equal(hook.importRefreshFailed,false);
  assert.equal(writes,1,'Recovery must use GET, never automatically repeat commit.');assert.ok(historyReads>=3);
});

test('an old hook save response cannot populate or unlock a remounted account',async()=>{
  let account='First',resolveSave;
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/auth/csrf')return response({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/applications?'))return response({content:[application(account)],totalPages:1});
    if(options.method==='PUT')return new Promise(resolve=>{resolveSave=resolve;});
    throw Error('Unexpected request '+url);
  };
  await mountProbe(id);assert.equal(hook.apps[0].company,'First');
  let save;
  await act(async()=>{save=hook.save({...record,appliedDate:'',deadline:'',followUpDate:''},targetId,0);save.catch(()=>{});});
  assert.equal(typeof resolveSave,'function');
  account='Second';await mountProbe(targetId);assert.equal(hook.apps[0].company,'Second');
  await act(async()=>resolveSave(response({...application('Old response'),version:1})));
  await assert.rejects(save,/earlier workspace/);
  assert.equal(hook.apps[0].company,'Second');assert.equal(hook.busy,false);
});

function RecoveryWorkspace(){
  hook=useApplications(id);const [open,setOpen]=useState(true);
  return createElement('div',null,createElement('button',{onClick:()=>setOpen(true)},'Open import'),open&&createElement(ApplicationImportDialog,{
    onClose:()=>setOpen(false),commit:hook.commitImport,pendingId:hook.pendingImport?.previewId,recoveryPreview:hook.recoveryPreview,
    retiredImport:hook.retiredImport,acknowledgeRetiredImport:hook.acknowledgeRetiredImport,requestBusy:hook.requestBusy,
    receipt:hook.importReceipt,refreshFailed:hook.importRefreshFailed,checkStatus:hook.reconcileImport,refresh:hook.retryImportRefresh
  }));
}
async function mountRecoveryWorkspace(){
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(StrictMode,null,createElement(RecoveryWorkspace))));
}
async function chooseCsv(){
  const file=container.querySelector('#import-file');Object.defineProperty(file,'files',{value:[new File(['Company,Role\nAcme,Intern\n'],'test.csv',{type:'text/csv'})]});
  await act(async()=>file.dispatchEvent(new Event('change',{bubbles:true})));
}
async function acknowledgeUpdates(){
  await click(button('Review final confirmation'));
  const acknowledgement=[...container.querySelectorAll('label')].find(l=>l.textContent.includes('I understand that 1 existing applications will be updated'));
  await click(acknowledgement.querySelector('input'));
}
for(const terminal of [404,410])for(const completion of ['direct','reconcile'])test(`terminal ${terminal} acknowledgement allows a new ${completion} receipt to survive dialog reopen`,async()=>{
  const next=preview({id:targetId}),receipt={importId:targetId,receiptId:'30000000-0000-0000-0000-000000000003',completedAt:'2026-10-15T12:05:00Z',retainUntil:'2026-11-14T12:05:00Z',counts:next.counts,rows:[]};
  let writes=0,statusReads=0;
  sessionStorage.setItem('campus:pending-import',JSON.stringify({ownerId:id,previewId:id}));
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return response({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url.startsWith('/api/applications?'))return response({content:[application()],totalPages:1});
    if(url===`/api/application-imports/${id}`)return response({code:terminal===404?'IMPORT_NOT_FOUND':'IMPORT_PREVIEW_EXPIRED',message:'Review no longer available'},terminal);
    if(url==='/api/application-imports/preview')return response(next,201);
    if(url===`/api/application-imports/${targetId}/commit`){writes++;return completion==='direct'?response(receipt):new Response('{',{headers:{'Content-Type':'application/json'}});}
    if(url===`/api/application-imports/${targetId}`){statusReads++;return response({status:'COMPLETED',receipt});}
    throw Error('Unexpected request '+url);
  };
  await mountRecoveryWorkspace();assert.equal(hook.retiredImport.id,id);assert.ok(container.textContent.includes('The previous review is no longer available'));
  await act(async()=>assert.rejects(hook.commitImport(next),/fresh review|retired review|acknowledge/i));assert.equal(writes,0);
  if(terminal===404){let acknowledged;await act(async()=>{acknowledged=hook.acknowledgeRetiredImport(id,false);});assert.equal(acknowledged,false);assert.equal(hook.retiredImport.id,id);}
  const restart=button('Start a fresh review');assert.equal(restart.disabled,terminal===404);
  if(terminal===404){await click([...container.querySelectorAll('label')].find(l=>l.textContent.includes('creating a fresh import could duplicate')).querySelector('input'));}
  await click(restart);assert.equal(hook.retiredImport,null,'Acknowledgement must retire the hook gate, rather than only hiding it in this dialog.');
  await chooseCsv();await click(button('Read file and show columns'));await acknowledgeUpdates();
  await click(button('Import 0 new applications and update 1 existing applications'));
  if(completion==='reconcile'){assert.equal(hook.pendingImport.previewId,targetId);await click(button('Check status'));assert.equal(statusReads,1);}
  assert.equal(writes,1);assert.equal(hook.importReceipt.receiptId,receipt.receiptId);
  await click(button('Close'));assert.equal(container.querySelector('dialog'),null);await click(button('Open import'));
  assert.ok(container.textContent.includes(receipt.receiptId));assert.ok(button('Download results'));assert.equal(container.textContent.includes('The previous review is no longer available'),false);
});

for(const lineage of ['predecessor','successor'])test(`unchanged replacement preview loss invalidates confirmation until ${lineage} GET recovery without any commit POST`,{timeout:5000},async()=>{
  let previewWrites=0,commitWrites=0,statusReads=0,finishReplacement;
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return response({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url==='/api/application-imports/preview'){
      if(++previewWrites===1)return response(preview(),201);
      return new Promise((resolve,reject)=>{finishReplacement=()=>reject(new Error('Lost replacement response'));});
    }
    if(url===`/api/application-imports/${id}`){statusReads++;return response(lineage==='successor'?{status:'SUPERSEDED',successorId:targetId}:{status:'PREVIEW',preview:preview()});}
    if(url===`/api/application-imports/${targetId}`){statusReads++;return response({status:'PREVIEW',preview:preview({id:targetId})});}
    if(url.endsWith('/commit')){commitWrites++;throw Error('Unexpected commit');}
    throw Error('Unexpected request '+url);
  };
  await render({recoveryPreview:null,commit:async()=>{commitWrites++;throw Error('Unexpected commit');}});
  await chooseCsv();await click(button('Read file and show columns'));await acknowledgeUpdates();
  assert.equal(button('Import 0 new applications and update 1 existing applications').disabled,false);
  await click(button('Create revised preview'));
  assert.equal(button('Review final confirmation').disabled,true);assert.equal(!!button('Import 0 new applications and update 1 existing applications'),false,'Beginning a replacement must reset confirmation and acknowledgement.');
  await act(async()=>finishReplacement());assert.equal(button('Review final confirmation').disabled,true);
  assert.equal(commitWrites,0);await click(button('Recover preview status'));
  assert.equal(statusReads,lineage==='successor'?2:1);assert.equal(previewWrites,2,'Status recovery cannot upload the unchanged file again.');assert.equal(commitWrites,0);
  assert.equal(button('Review final confirmation').disabled,false);await click(button('Review final confirmation'));
  assert.equal(button('Import 0 new applications and update 1 existing applications').disabled,true,'Recovered review requires a new acknowledgement.');
});
