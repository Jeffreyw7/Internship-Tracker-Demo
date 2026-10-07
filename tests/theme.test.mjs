import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { normalizePreference, resolveTheme, readPreference, writePreference, BOOTSTRAP_KEY } from '../src/theme/theme.ts';
import { createThemeController } from '../src/theme/themeController.ts';
import { preferencesApi } from '../src/data/preferencesApi.ts';
import { request } from '../src/data/api.ts';

const deferred = () => { let resolve, reject; const promise = new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; };
const flush = async () => { await new Promise(resolve=>setImmediate(resolve)); };
function setup() {
  const reads=[], writes=[], cache=[];
  const controller=createThemeController({initialPreference:'system', anonymousPreference:()=> 'light',
    read:()=>{const d=deferred();reads.push(d);return d.promise;},
    write:value=>{const d=deferred();writes.push({value,...d});return d.promise;},
    cache:(value,anonymous)=>cache.push({value,anonymous}), notify:()=>{}});
  return {controller,reads,writes,cache};
}

test('theme preferences resolve independently of OS when explicit',()=>{
  for(const value of [null,undefined,'DARK','bad',{},'']) assert.equal(normalizePreference(value),'system');
  for(const dark of [false,true]) {
    assert.equal(resolveTheme('light',dark),'light');assert.equal(resolveTheme('dark',dark),'dark');
    assert.equal(resolveTheme('system',dark),dark?'dark':'light');
  }
  const denied={getItem(){throw Error('denied');},setItem(){throw Error('denied');}};
  assert.equal(readPreference(denied,BOOTSTRAP_KEY),'system');
  assert.doesNotThrow(()=>writePreference(denied,BOOTSTRAP_KEY,'dark'));
});

test('bootstrap matches resolver and survives blocked storage/media queries',()=>{
  const script=readFileSync(new URL('../public/theme-init.js',import.meta.url),'utf8');
  for(const preference of ['light','dark','system','invalid',null]) for(const dark of [false,true]) {
    const root={dataset:{},style:{}}, meta={setAttribute(key,value){this[key]=value;}};
    runInNewContext(script,{document:{documentElement:root,querySelector:()=>meta},localStorage:{getItem:()=>preference},window:{matchMedia:()=>({matches:dark})}});
    assert.equal(root.dataset.theme,resolveTheme(normalizePreference(preference),dark));
    assert.ok(meta.content);
  }
  const root={dataset:{},style:{}};
  runInNewContext(script,{document:{documentElement:root,querySelector:()=>null},get localStorage(){throw Error('denied');},window:{}});
  assert.equal(root.dataset.theme,'light');
});

test('account settings load before editing and do not upload anonymous choices',async()=>{
  const {controller:c,reads,writes}=setup();
  assert.equal(c.getSnapshot().canEdit,false);
  c.bindAccount('a'); c.setPreference('dark');assert.equal(writes.length,0);
  reads[0].resolve('dark');await flush();
  assert.equal(c.getSnapshot().preference,'dark');assert.equal(c.getSnapshot().canEdit,true);
  c.bindAccount(null);assert.equal(c.getSnapshot().preference,'light');
  c.setPreference('dark');assert.equal(writes.length,0);
});

test('late responses from another account cannot change the new account',async()=>{
  const {controller:c,reads}=setup();c.bindAccount('a');c.bindAccount('b');
  reads[1].resolve('light');await flush();reads[0].resolve('dark');await flush();
  assert.equal(c.getSnapshot().preference,'light');
});

test('refreshes coalesce and a stale GET cannot overwrite a newer selection',async()=>{
  const {controller:c,reads,writes}=setup();c.bindAccount('a');reads[0].resolve('system');await flush();
  c.refresh();c.refresh();assert.equal(reads.length,2);
  c.setPreference('dark');reads[1].resolve('light');await flush();
  assert.equal(c.getSnapshot().preference,'dark');
  assert.equal(c.getSnapshot().syncState,'saving');
  writes[0].resolve('dark');await flush();assert.equal(c.getSnapshot().syncState,'synced');
});

test('failed writes preserve local choice, require explicit retry, and block refresh overwrite',async()=>{
  const {controller:c,reads,writes}=setup();c.bindAccount('a');reads[0].resolve('light');await flush();
  c.setPreference('dark');writes[0].reject(Error('offline'));await flush();
  assert.equal(c.getSnapshot().preference,'dark');assert.equal(c.getSnapshot().syncState,'error');
  c.refresh();assert.equal(reads.length,1);assert.equal(writes.length,1);
  c.retry();assert.equal(writes.length,2);writes[1].resolve('dark');await flush();
  assert.equal(c.getSnapshot().syncState,'synced');
});

test('initial read failure can retry, while background failure retains the usable theme',async()=>{
  const {controller:c,reads}=setup();c.bindAccount('a');reads[0].reject(Error('offline'));await flush();
  assert.equal(c.getSnapshot().canEdit,false);c.retry();reads[1].resolve('dark');await flush();
  c.refresh();reads[2].reject(Error('offline'));await flush();
  assert.equal(c.getSnapshot().preference,'dark');assert.equal(c.getSnapshot().canEdit,true);
});

test('logout invalidates a pending save and dispose invalidates pending reads',async()=>{
  const {controller:c,reads,writes}=setup();c.bindAccount('a');reads[0].resolve('light');await flush();
  c.setPreference('dark');c.bindAccount(null);writes[0].resolve('dark');await flush();
  assert.equal(c.getSnapshot().preference,'light');assert.equal(c.getSnapshot().syncState,'local');
  c.bindAccount('b');c.dispose();reads[1].resolve('dark');await flush();
  assert.notEqual(c.getSnapshot().preference,'dark');
});

test('preference transport sends CSRF-protected JSON and rejects malformed server values',async()=>{
  const original=globalThis.fetch, calls=[];
  try {
    globalThis.fetch=async(url,options)=>{
      calls.push({url,options});
      return new Response(JSON.stringify(url==='/api/auth/csrf'?{token:'token',headerName:'X-CSRF-TOKEN'}:{themePreference:'dark'}),{headers:{'Content-Type':'application/json'}});
    };
    assert.equal(await preferencesApi.write('dark'),'dark');
    const call=calls.at(-1);assert.equal(call.url,'/api/me/preferences');assert.equal(call.options.method,'PUT');
    assert.equal(call.options.headers.get('X-CSRF-TOKEN'),'token');assert.equal(call.options.credentials,'same-origin');
    assert.deepEqual(JSON.parse(call.options.body),{themePreference:'dark'});
    globalThis.fetch=async()=>new Response('{"themePreference":"unexpected"}',{headers:{'Content-Type':'application/json'}});
    await assert.rejects(preferencesApi.read(),/Invalid account appearance/);
  } finally {globalThis.fetch=original;}
});

test('stale preference requests cannot expire a newer authenticated session',async()=>{
  const oldFetch=globalThis.fetch,oldWindow=globalThis.window;
  const events=new EventTarget();let expired=0;
  events.addEventListener('campus:session-expired',()=>expired++);
  globalThis.window=events;
  globalThis.fetch=async()=>new Response('{"message":"Expired"}',{status:401,headers:{'Content-Type':'application/json'}});
  try {
    await assert.rejects(request('/me/preferences',{},()=>false));assert.equal(expired,0);
    await assert.rejects(request('/me/preferences',{},()=>true));assert.equal(expired,1);
  } finally {globalThis.fetch=oldFetch;if(oldWindow===undefined)delete globalThis.window;else globalThis.window=oldWindow;}
});

test('controller supplies account guards to the real preference transport',async()=>{
  const oldFetch=globalThis.fetch,oldWindow=globalThis.window,reads=[];
  globalThis.window=new EventTarget();let expired=0;
  window.addEventListener('campus:session-expired',()=>expired++);
  globalThis.fetch=()=>{const d=deferred();reads.push(d);return d.promise;};
  try {
    const c=createThemeController({initialPreference:'system',anonymousPreference:()=> 'system',read:preferencesApi.read,write:preferencesApi.write,cache(){},notify(){}});
    c.bindAccount('a');c.bindAccount('b');
    reads[1].resolve(new Response('{"themePreference":"dark"}',{headers:{'Content-Type':'application/json'}}));await flush();
    reads[0].resolve(new Response('{"message":"Expired"}',{status:401,headers:{'Content-Type':'application/json'}}));await flush();
    assert.equal(expired,0);assert.equal(c.getSnapshot().preference,'dark');
  } finally {globalThis.fetch=oldFetch;if(oldWindow===undefined)delete globalThis.window;else globalThis.window=oldWindow;}
});
