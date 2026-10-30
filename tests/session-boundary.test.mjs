import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';

const originalFetch=globalThis.fetch, originalWindow=globalThis.window;
afterEach(()=>{globalThis.fetch=originalFetch;if(originalWindow===undefined)delete globalThis.window;else globalThis.window=originalWindow;});
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const a={id:'account-a',displayName:'Alice',email:'a@example.test'}, b={id:'account-b',displayName:'Bob',email:'b@example.test'};

test('a tab keeps its expected account when another tab changes the shared session cookie',async()=>{
  globalThis.window=new EventTarget();
  let expired=0;window.addEventListener('campus:session-expired',()=>expired++);
  let serverUser=a,writes=0;
  const tab=await import('../src/data/api.ts?tab=account-boundary');
  globalThis.fetch=async(url,init)=>{
    if(url==='/api/auth/me')return json(serverUser);
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    if(init.headers.has('X-Campus-Account')&&init.headers.get('X-Campus-Account')!==serverUser.id)return json({code:'ACCOUNT_CHANGED',message:'Account changed'},401);
    if(init.method==='POST')writes++;
    return json({owner:serverUser.id});
  };
  await tab.authApi.me();
  const oldGuard=tab.sessionGuard();
  serverUser=b;
  await assert.rejects(tab.request('/applications',{method:'POST',...tab.jsonBody({company:'Old draft'})}),e=>e.status===401);
  assert.equal(writes,0);assert.equal(expired,1);assert.equal(oldGuard(),false);
  await tab.authApi.me();
  assert.deepEqual(await tab.request('/applications'),{owner:'account-b'});
});

test('a profile recheck invalidates pending work from the previous account',async()=>{
  const tab=await import('../src/data/api.ts?tab=profile-recheck');
  let serverUser=a,finish;
  globalThis.fetch=async(url)=>url==='/api/auth/me'?json(serverUser):new Promise(resolve=>{finish=resolve;});
  await tab.authApi.me();
  const pending=tab.request('/applications');const rejected=assert.rejects(pending,tab.StaleSessionError);
  serverUser=b;await tab.authApi.me();
  finish(json({owner:'account-a'}));await rejected;
});

test('a late bodyless response cannot complete work after the account has changed',async()=>{
  const tab=await import('../src/data/api.ts?tab=bodyless-recheck');
  let serverUser=a,finish;
  globalThis.fetch=async(url)=>url==='/api/auth/me'?json(serverUser):new Promise(resolve=>{finish=resolve;});
  await tab.authApi.me();
  const pending=tab.request('/applications');const rejected=assert.rejects(pending,tab.StaleSessionError);
  serverUser=b;await tab.authApi.me();
  finish(new Response(null,{status:204}));await rejected;
});


test('overlapping initial profile requests can both establish the same account',async()=>{
  const tab=await import('../src/data/api.ts?tab=overlapping-profiles');
  const completions=[];
  globalThis.fetch=async()=>new Promise(resolve=>completions.push(resolve));
  const first=tab.authApi.me(),second=tab.authApi.me();
  completions[0](json(a));assert.deepEqual(await first,a);
  completions[1](json(a));assert.deepEqual(await second,a);
});


test('serialized logout retains the expected account before a shared cookie change',async()=>{
  globalThis.window=new EventTarget();
  const tab=await import('../src/data/api.ts?tab=serialized-logout');
  let serverUser=a,logoutWrites=0,sentAccount;
  globalThis.fetch=async(url,init)=>{
    if(url==='/api/auth/me')return json(serverUser);
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    sentAccount=init.headers.get('X-Campus-Account');
    if(sentAccount!==serverUser.id)return json({code:'ACCOUNT_CHANGED',message:'Account changed'},401);
    logoutWrites++;return new Response(null,{status:204});
  };
  await tab.authApi.me();serverUser=b;
  await assert.rejects(tab.authApi.logout(),e=>e.code==='AUTH_SESSION_CHANGED');
  assert.equal(sentAccount,a.id);assert.equal(logoutWrites,0);
});

test('login binds subsequent account requests and failed logout preserves their boundary',async()=>{
  const tab=await import('../src/data/api.ts?tab=serialized-login');
  let sentAccount;
  globalThis.fetch=async(url,init)=>{
    if(url==='/api/auth/csrf')return json({token:'token',headerName:'X-CSRF-TOKEN'});
    if(url==='/api/auth/login')return json(b);
    sentAccount=init.headers.get('X-Campus-Account');
    return url==='/api/auth/logout'?json({message:'Temporarily unavailable'},503):json({owner:b.id});
  };
  await tab.authApi.login(b.email,'Synthetic passphrase');
  await assert.rejects(tab.authApi.logout(),e=>e.status===503);
  assert.deepEqual(await tab.request('/applications'),{owner:b.id});
  assert.equal(sentAccount,b.id);
});
