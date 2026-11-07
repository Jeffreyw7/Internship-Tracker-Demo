// Exercise conflict and unknown-create recovery through the real drawer UI.
import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {registerHooks} from 'node:module';
import ts from 'typescript';
import {JSDOM} from 'jsdom';

registerHooks({
  resolve(specifier,context,next) {
    try {return next(specifier,context);} catch(error) {
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
for(const key of ['window','document','HTMLElement','HTMLDialogElement','HTMLInputElement','HTMLTextAreaElement','MouseEvent','Event'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
dom.window.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
dom.window.HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
dom.window.HTMLElement.prototype.scrollIntoView=function(){};
const {createElement,act}=await import('react');
const {createRoot}=await import('react-dom/client');
const {ApplicationDrawer}=await import('../src/components/ApplicationDrawer.tsx');
const {ApiError}=await import('../src/data/api.ts');
const base={id:'10000000-0000-0000-0000-000000000001',company:'Acme',role:'Intern',location:'Boston',status:'Saved',
  appliedDate:'',deadline:'',followUpDate:'',notes:'Original notes',stages:[],version:2,sample:false,deleted:false,
  createdAt:'2026-10-15T12:00:00Z',updatedAt:'2026-10-15T12:00:00Z'};
const stale=()=>new ApiError(409,'This application changed.');
let root,container,props;
async function render(patch={}) {
  if(!root){container=document.createElement('div');document.body.append(container);root=createRoot(container);
    props={application:base,isNew:false,onClose(){},onSave:async()=>{},onDelete:async()=>{}};}
  props={...props,...patch};await act(async()=>root.render(createElement(ApplicationDrawer,props)));
}
const button=text=>[...container.querySelectorAll('button')].find(b=>b.textContent.includes(text));
const click=async element=>{assert.ok(element,'Expected control is present');await act(async()=>element.click());};
async function input(selector,value) {
  const element=container.querySelector(selector);assert.ok(element);
  const prototype=element instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  await act(async()=>{Object.getOwnPropertyDescriptor(prototype,'value').set.call(element,value);element.dispatchEvent(new Event('input',{bubbles:true}));});
}
const submit=async()=>act(async()=>container.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
async function edit(patch={}){await render(patch);await click(button('Edit application'));}
afterEach(async()=>{if(root)await act(async()=>root.unmount());root=undefined;container?.remove();});

test('409 preserves the draft, blocks saving, then explicitly merges untouched remote values using the latest version',async()=>{
  const writes=[];const latest={...base,version:3,role:'Senior Intern',location:'Remote'};
  await edit({onSave:async(...args)=>{writes.push(structuredClone(args));if(writes.length===1)throw stale();},onLoadLatest:async()=>latest});
  await input('#notes','My preparation');await submit();
  assert.equal(container.querySelector('#notes').value,'My preparation');
  assert.equal(button('Save changes').disabled,true);
  assert.ok(container.textContent.includes('Senior Intern'));assert.ok(container.textContent.includes('Remote'));
  await render({application:latest});await submit();assert.equal(writes.length,1,'Even programmatic submits cannot bypass conflict recovery.');
  await click(button('Reapply my changes'));assert.equal(writes.length,1,'Reapplying prepares a draft without writing.');
  assert.equal(button('Save changes').disabled,false);await submit();
  assert.equal(writes[1][2],3);assert.equal(writes[1][0].notes,'My preparation');
  assert.equal(writes[1][0].role,'Senior Intern');assert.equal(writes[1][0].location,'Remote');
});

test('overlapping notes and interview stages require acknowledgement before deliberately keeping local changes',async()=>{
  const stage={id:'stage-one',name:'Screen',date:'',state:'Planned'};
  const initial={...base,stages:[stage]};const latest={...initial,version:3,notes:'Remote notes',stages:[{...stage,name:'Remote screen'}]};
  const writes=[];
  await edit({application:initial,onLoadLatest:async()=>latest,onSave:async(...args)=>{writes.push(structuredClone(args));if(writes.length===1)throw stale();}});
  await input('#notes','Local notes');await input('#name-stage-one','Local screen');await submit();
  assert.equal(button('Reapply my changes').disabled,true);
  assert.ok(container.textContent.includes('Remote notes'));assert.ok(container.textContent.includes('Remote screen'));
  await click(container.querySelector('input[type="checkbox"]'));assert.equal(button('Reapply my changes').disabled,false);
  await input('#notes','Local notes revised');assert.equal(button('Reapply my changes').disabled,true,'Changing the reviewed draft resets acknowledgement.');
  await click(container.querySelector('input[type="checkbox"]'));await click(button('Reapply my changes'));await submit();
  assert.equal(writes[1][2],3);assert.equal(writes[1][0].notes,'Local notes revised');assert.equal(writes[1][0].stages[0].name,'Local screen');
});

test('using latest saved values discards the draft only after the user chooses it',async()=>{
  const writes=[];const latest={...base,version:4,notes:'Remote notes'};
  await edit({onLoadLatest:async()=>latest,onSave:async(...args)=>{writes.push(structuredClone(args));if(writes.length===1)throw stale();}});
  await input('#notes','Local notes');await submit();assert.equal(container.querySelector('#notes').value,'Local notes');
  await click(button('Use latest saved values'));assert.equal(container.querySelector('#notes').value,'Remote notes');
  assert.equal(writes.length,1);await submit();assert.equal(writes[1][2],4);assert.equal(writes[1][0].notes,'Remote notes');
});

test('a second conflict requires a new explicit recovery and adopts the newer version',async()=>{
  const writes=[];let latest={...base,version:3,location:'Remote'};
  await edit({onLoadLatest:async()=>latest,onSave:async(...args)=>{writes.push(structuredClone(args));if(writes.length<3)throw stale();}});
  await input('#notes','Local notes');await submit();await click(button('Reapply my changes'));
  latest={...latest,version:4,role:'Research Intern'};await submit();
  assert.equal(button('Save changes').disabled,true);assert.equal(container.querySelector('#notes').value,'Local notes');
  await click(button('Reapply my changes'));await submit();
  assert.deepEqual(writes.map(w=>w[2]),[2,3,4]);assert.equal(writes[2][0].role,'Research Intern');assert.equal(writes[2][0].location,'Remote');
});

test('a failed latest read preserves the draft and only retries the read before recovery',async()=>{
  let reads=0,writes=0;
  await edit({onSave:async()=>{writes++;throw stale();},onLoadLatest:async()=>{if(++reads===1)throw Error('Network unavailable');return {...base,version:3};}});
  await input('#notes','Keep this');await submit();
  assert.equal(button('Save changes').disabled,true);assert.equal(container.querySelector('#notes').value,'Keep this');
  assert.ok(container.textContent.includes('Network unavailable'));assert.equal(writes,1);
  await click(button('Check latest saved version'));assert.equal(reads,2);assert.equal(writes,1);
  await click(button('Reapply my changes'));assert.equal(button('Save changes').disabled,false);
});

for(const deleted of [null,{...base,version:3,deleted:true}])test(`a ${deleted?'soft-deleted':'missing'} latest record keeps local text and blocks stale saves`,async()=>{
  let writes=0;
  await edit({onSave:async()=>{writes++;throw stale();},onLoadLatest:async()=>deleted});
  await input('#notes','Unsaved notes');await submit();await render({application:null});
  assert.equal(container.querySelector('#notes').value,'Unsaved notes');assert.equal(button('Save changes').disabled,true);
  assert.match(container.textContent,/no longer available|deleted/i);assert.equal(button('Reapply my changes'),undefined);
  await submit();assert.equal(writes,1);
});

test('an update rejected as missing also blocks repeated saves and preserves the draft',async()=>{
  let writes=0;
  await edit({onSave:async()=>{writes++;throw new ApiError(404,'Application not found.');},onLoadLatest:async()=>null});
  await input('#notes','Keep my work');await submit();
  assert.equal(button('Save changes').disabled,true);assert.equal(container.querySelector('#notes').value,'Keep my work');
  await submit();assert.equal(writes,1);
});

test('a late conflict read cannot restore a dismissed conflict in a new editing session',async()=>{
  let reads=0,finish;
  await edit({onSave:async()=>{throw stale();},onLoadLatest:async()=>{if(++reads===1)throw Error('Offline');return new Promise(resolve=>{finish=resolve;});}});
  await input('#notes','First draft');await submit();await click(button('Check latest saved version'));
  await click(button('Cancel'));await click(button('Edit application'));await input('#notes','New draft');
  await act(async()=>finish({...base,version:3,notes:'Remote notes'}));
  assert.equal(button('Save changes').disabled,false);assert.equal(container.querySelector('#notes').value,'New draft');
  assert.equal(button('Reapply my changes'),undefined);
});

test('two submits in one event turn send one write',async()=>{
  let writes=0,finish;
  await edit({onSave:async()=>{writes++;await new Promise(resolve=>{finish=resolve;});}});
  await act(async()=>{const form=container.querySelector('form');for(let i=0;i<2;i++)form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));});
  assert.equal(writes,1);await act(async()=>finish());
});

test('unknown create freezes fields and exposes explicit status, retry, and cancellation without automatic writes',async()=>{
  let writes=0,checks=0,cancels=0;const submitted=[];
  await render({application:null,isNew:true,onSave:async draft=>{writes++;submitted.push(structuredClone(draft));throw Error('Save outcome unknown');},
    onCheckCreate:async()=>{checks++;throw Error('Still pending');},onCancelCreate:async()=>{cancels++;}});
  await input('input[placeholder="e.g. Figma"]','Acme');await input('input[placeholder="e.g. Software Engineer Intern"]','Intern');await submit();
  await render({createPending:true});assert.equal(container.querySelector('#notes').readOnly,true);assert.equal(writes,1);
  await click(button('Check save status'));assert.equal(checks,1);assert.equal(writes,1);assert.ok(container.textContent.includes('Still pending'));
  await click(button('Retry save'));assert.equal(writes,2);assert.deepEqual(submitted[1],submitted[0]);
  await click(button('Cancel pending save'));assert.equal(cancels,1);assert.equal(writes,2);
});

test('pending create values remain readable and copyable while frozen, then resume editing after recovery',async()=>{
  const writes=[];
  await render({application:null,isNew:true,onSave:async draft=>writes.push(structuredClone(draft)),onCheckCreate:async()=>{},onCancelCreate:async()=>{}});
  await input('input[placeholder="e.g. Figma"]','Acme');await input('input[placeholder="e.g. Software Engineer Intern"]','Intern');
  await input('#notes','My only copy of these notes');await click(button('Add stage'));
  await input('input[placeholder="e.g. Technical interview"]','Recruiter screen');
  await render({createPending:true});
  const body=container.querySelector('.drawer-body'),notes=container.querySelector('#notes');
  assert.equal(body.hasAttribute('inert'),false);assert.notEqual(body.getAttribute('aria-hidden'),'true');
  for(const field of body.querySelectorAll('input,textarea')){assert.equal(field.readOnly,true);assert.equal(field.disabled,false);}
  for(const select of body.querySelectorAll('select'))assert.equal(select.disabled,true);
  assert.equal(button('Add stage').disabled,true);assert.equal(container.querySelector('.remove-stage').disabled,true);
  notes.focus();notes.select();assert.equal(document.activeElement,notes);
  assert.equal(notes.value.slice(notes.selectionStart,notes.selectionEnd),'My only copy of these notes');
  for(const label of ['Check save status','Retry save','Cancel pending save'])assert.equal(button(label).disabled,false);
  await input('#notes','Attempted change');await click(button('Retry save'));
  assert.equal(writes[0].notes,'My only copy of these notes');assert.equal(writes[0].stages[0].name,'Recruiter screen');
  await render({createPending:false});
  for(const field of body.querySelectorAll('input,textarea'))assert.equal(field.readOnly,false);
  for(const select of body.querySelectorAll('select'))assert.equal(select.disabled,false);
  assert.equal(button('Add stage').disabled,false);assert.equal(container.querySelector('.remove-stage').disabled,false);
  assert.equal(notes.value,'My only copy of these notes');await input('#notes','Ready to save again');await submit();
  assert.equal(writes[1].notes,'Ready to save again');assert.equal(writes[1].stages[0].name,'Recruiter screen');
});
