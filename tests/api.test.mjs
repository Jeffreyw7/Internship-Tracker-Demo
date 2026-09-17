import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { applicationApi, toPayload } from '../src/data/applicationApi.ts';
import { ApiError, request, refreshCsrf } from '../src/data/api.ts';
import { blankDraft, today, activities } from '../src/domain/application.ts';
import { filterApplications } from '../src/domain/selectors.ts';
import { sampleApplications } from './fixtures/seed.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const draft=()=>({...blankDraft(),company:' Example Labs ',role:'Java Intern',status:'Applied',appliedDate:today()});

test('JSON payload excludes server-owned metadata and converts empty dates to null',()=>{
  const payload=toPayload({...draft(),id:'server-id',ownerId:'forged',version:8,sample:true,deleted:true});
  assert.equal(payload.company,'Example Labs');assert.equal(payload.deadline,null);assert.equal(payload.followUpDate,null);
  assert.ok(!('ownerId' in payload));assert.ok(!('version' in payload));assert.ok(!('id' in payload));
});
test('mutations send CSRF, credentials, and the expected record version',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return url==='/api/auth/csrf'?json({token:'test-token',headerName:'X-CSRF-TOKEN'}):json({id:'app-1',version:3});};
  await refreshCsrf();await applicationApi.save(draft(),'app-1',2);
  const call=calls.at(-1);assert.equal(call.url,'/api/applications/app-1');assert.equal(call.options.method,'PUT');
  assert.equal(call.options.headers.get('If-Match'),'2');assert.equal(call.options.headers.get('X-CSRF-TOKEN'),'test-token');
  assert.equal(call.options.credentials,'same-origin');
});
test('conflicted updates reject and are never retried automatically',async()=>{
  let writes=0;globalThis.fetch=async(url)=>{if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});writes++;return json({message:'Changed elsewhere'},409);};
  await refreshCsrf();await assert.rejects(()=>applicationApi.save(draft(),'app-1',0),e=>e instanceof ApiError&&e.status===409);
  assert.equal(writes,1);
});
test('paged histories are fully loaded and duplicate boundaries are removed',async()=>{
  globalThis.fetch=async(url)=>url.endsWith('page=0')?json({content:[{id:'a'}],totalPages:2}):json({content:[{id:'a'},{id:'b'}],totalPages:2});
  assert.deepEqual((await applicationApi.list()).map(a=>a.id),['a','b']);
});
test('delete and undo use the version returned by the delete endpoint',async()=>{
  const calls=[];globalThis.fetch=async(url,options)=>{if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});calls.push({url,options});return json({id:'a',version:6,deleted:options.method==='DELETE'});};
  await refreshCsrf();const deleted=await applicationApi.remove({id:'a',version:5});await applicationApi.restoreDeleted(deleted);
  assert.equal(calls[0].options.headers.get('If-Match'),'5');assert.equal(calls[1].options.headers.get('If-Match'),'6');assert.equal(calls[1].url,'/api/applications/a/restore');
});
test('a missing backend does not silently fall back to browser storage',async()=>{
  globalThis.fetch=async()=>new Response('<html>Static page</html>',{headers:{'Content-Type':'text/html'}});
  await assert.rejects(()=>request('/applications'),e=>e instanceof ApiError&&e.status===503);
});
test('saved dataset supports combined search, status, sorting and follow-up filters',()=>{
  const apps=sampleApplications();const base={query:'',status:'All statuses',dueOnly:false,tab:'All applications',sort:'recent'};
  assert.equal(filterApplications(apps,{...base,status:'Interviewing',query:'Figma'}).length,1);
  assert.equal(filterApplications(apps,{...base,dueOnly:true}).length,2);
  assert.equal(filterApplications(apps,{...base,tab:'In progress'}).length,7);
  assert.equal(filterApplications(apps,{...base,sort:'company'})[0].company,'Adobe');
});
test('completed interviews and cleared follow-ups leave the activity list',()=>{
  const a=sampleApplications()[0];const initial=activities([a]);assert.equal(initial.length,2);
  assert.equal(activities([{...a,followUpDate:'',stages:a.stages.map(s=>({...s,state:'Completed'}))}]).length,0);
});
