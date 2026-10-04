// Tests client transport contracts, password rules, and derived views using stubbed HTTP responses.
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { applicationApi, toPayload } from '../src/data/applicationApi.ts';
import { ApiError, request, refreshCsrf, authApi } from '../src/data/api.ts';
import { blankDraft, today, activities } from '../src/domain/application.ts';
import { filterApplications } from '../src/domain/selectors.ts';
import { sampleApplications } from './fixtures/seed.ts';
import { passwordError } from '../src/domain/password.ts';
// Restore the real fetch implementation after each test to prevent shared mock state.
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
// Build small HTTP response fixtures for the API adapter tests.
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const draft=()=>({...blankDraft(),company:' Example Labs ',role:'Java Intern',status:'Applied',appliedDate:today()});

test('signup password policy checks complexity, character count, and UTF-8 boundaries',()=>{
  for(const value of ['', 'Abcdefghi1!', 'abcdefghij1!', 'ABCDEFGHIJ1!', 'Abcdefghijk!', 'Abcdefghijk1', 'Abcdefghij1 ', 'Ab1!'+'a'.repeat(69), 'Ab1!'+'é'.repeat(35), 'Ab1!'+'😀'.repeat(7)]) {
    assert.ok(passwordError(value), 'Expected rejected password');
  }
  for(const value of ['Abcdefghij1!', 'Ab1!'+'a'.repeat(68), 'Ab1!'+'é'.repeat(34), 'Ab1!'+'😀'.repeat(8), 'Àbcdefghij1!']) {
    assert.equal(passwordError(value), null);
  }
});

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

test('CSRF throttling preserves server message and retry interval',async()=>{
  let calls=0;
  globalThis.fetch=async()=>{calls++;return new Response(JSON.stringify({message:'Slow down'}),{
    status:429,headers:{'Content-Type':'application/json','Retry-After':'30'}});};
  await assert.rejects(()=>refreshCsrf(), e=>e instanceof ApiError&&e.status===429&&e.message.includes('Slow down')&&e.retryAfterSeconds===30);
  assert.equal(calls,1);
});
test('login throttling is never replayed and preserves retry interval',async()=>{
  let writes=0;
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    writes++;return new Response(JSON.stringify({message:'Slow down'}),{status:429,headers:{'Retry-After':'5'}});
  };
  await refreshCsrf();
  await assert.rejects(()=>request('/auth/login',{method:'POST'}),e=>e instanceof ApiError&&e.status===429&&e.retryAfterSeconds===5);
  assert.equal(writes,1);
});
test('invalid Retry-After does not hide the original API error',async()=>{
  globalThis.fetch=async()=>new Response(JSON.stringify({message:'Slow down'}),{status:429,headers:{'Retry-After':'nonsense'}});
  await assert.rejects(()=>request('/applications'),e=>e instanceof ApiError&&e.status===429&&e.retryAfterSeconds===undefined);
});

for(const operation of ['list','save','remove','restoreDeleted']) {
  test(`an old application ${operation} response cannot expire a newer login`,async()=>{
    const previousWindow=globalThis.window;
    const events=new EventTarget();globalThis.window=events;
    let expired=0;events.addEventListener('campus:session-expired',()=>expired++);
    let finishOld;
    globalThis.fetch=async(url)=>{
      if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
      if(url==='/api/auth/logout')return new Response(null,{status:204});
      if(url==='/api/auth/login')return json({id:'new-account',displayName:'New',email:'new@example.test'});
      return new Promise(resolve=>{finishOld=resolve;});
    };
    try {
      await refreshCsrf();
      const pending=operation==='list'?applicationApi.list():operation==='save'?applicationApi.save(draft(),'a',1):applicationApi[operation]({id:'a',version:1});
      const rejected=assert.rejects(pending,e=>e instanceof ApiError&&e.status===401);
      await authApi.logout();await authApi.login('new@example.test','Synthetic-password-123!');
      finishOld(json({message:'Old session ended'},401));await rejected;
      assert.equal(expired,0);
      globalThis.fetch=async()=>json({message:'Current session ended'},401);
      await assert.rejects(applicationApi.list(),e=>e.status===401);
      assert.equal(expired,1);
    } finally {if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;}
  });
}

test('requests started during login cannot expire the completed login',async()=>{
  const previousWindow=globalThis.window;
  const events=new EventTarget();globalThis.window=events;
  let expired=0;events.addEventListener('campus:session-expired',()=>expired++);
  let finishLogin,loginStarted,finishOld;
  const started=new Promise(resolve=>{loginStarted=resolve;});
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url==='/api/auth/login')return new Promise(resolve=>{finishLogin=resolve;loginStarted();});
    return new Promise(resolve=>{finishOld=resolve;});
  };
  try {
    const login=authApi.login('new@example.test','Synthetic-password-123!');await started;
    const pending=applicationApi.list();const rejected=assert.rejects(pending,e=>e.status===401);
    finishLogin(json({id:'new-account',displayName:'New',email:'new@example.test'}));await login;
    finishOld(json({message:'Old session ended'},401));await rejected;
    assert.equal(expired,0);
  } finally {if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;}
});

test('every page of an old history load retains its original session guard',async()=>{
  const previousWindow=globalThis.window;
  const events=new EventTarget();globalThis.window=events;
  let expired=0;events.addEventListener('campus:session-expired',()=>expired++);
  let finishFirst;
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url==='/api/auth/login')return json({id:'new-account',displayName:'New',email:'new@example.test'});
    if(url.endsWith('page=0'))return new Promise(resolve=>{finishFirst=resolve;});
    return json({message:'Old session ended'},401);
  };
  try {
    const pending=applicationApi.list();const rejected=assert.rejects(pending,e=>e.status===401);
    await authApi.login('new@example.test','Synthetic-password-123!');
    finishFirst(json({content:[{id:'old-app'}],totalPages:2}));await rejected;
    assert.equal(expired,0);
  } finally {if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;}
});
