import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import * as api from '../src/data/api.ts';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const json = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}});
const token = 'a'.repeat(42) + 'A';
const csrf = {token: 'fresh-token', headerName: 'X-CSRF-TOKEN'};

test('a stale auth write rejects before trying to enqueue CSRF behind its own transition', async () => {
  // Isolate the deliberately deadlocking regression so a failure cannot poison
  // the queue used by the other transport tests in this process.
  const source=new URL('../src/data/api.ts',import.meta.url).href;
  const script=`
    import assert from 'node:assert/strict';
    import * as api from ${JSON.stringify(source)};
    globalThis.window=new EventTarget();
    let channel;
    globalThis.BroadcastChannel=class { constructor() { channel=this; } postMessage() {} close() {} };
    const unsubscribe=api.subscribeSessionChanges(() => {});
    const calls=[];
    globalThis.fetch=async url => { calls.push(url); return new Response(JSON.stringify({token:'fresh',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}}); };
    const timeout=setTimeout(() => { console.error('Stale auth write deadlocked behind its own CSRF refresh'); process.exit(2); },1000);
    try {
      await assert.rejects(api.authTransition(async current => {
        await api.refreshAuthCsrf(current);
        channel.onmessage({data:'session-changed'});
        return api.request('/auth/password-reset/confirm',{method:'POST'},current);
      }),error => error.code==='AUTH_SESSION_CHANGED');
      assert.deepEqual(calls,['/api/auth/csrf']);
    } finally { clearTimeout(timeout); unsubscribe(); }
  `;
  await promisify(execFile)(process.execPath,['--input-type=module','--eval',script],{timeout:3000});
});

test('a stale CSRF response cannot replace the token from a completed login', async () => {
  let finishOld;
  let csrfCalls = 0;
  globalThis.fetch = async url => {
    if (url === '/api/auth/csrf') {
      if (++csrfCalls === 1) return new Promise(resolve => { finishOld = resolve; });
      return json({token: 'new-token', headerName: 'X-CSRF-TOKEN'});
    }
    return json({id:'new', displayName:'New', email:'new@example.test'});
  };
  const old = api.refreshCsrf();
  await Promise.resolve();
  const login = api.authApi.login('new@example.test', 'long enough password');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(csrfCalls, 1, 'The pending CSRF request must finish before login starts');
  finishOld(json({token:'old-token', headerName:'X-CSRF-TOKEN'}));
  await Promise.all([old, login]);
  let sent;
  globalThis.fetch = async (_url, options) => { sent = options.headers.get('X-CSRF-TOKEN'); return json({}); };
  await api.request('/applications', {method:'POST'});
  assert.equal(sent, 'new-token');
});

test('CSRF fetched for an expired session is discarded before another write', async () => {
  const previousWindow = globalThis.window;
  globalThis.window = new EventTarget();
  let finishCsrf;
  globalThis.fetch = async url => url === '/api/auth/csrf'
    ? new Promise(resolve => { finishCsrf = resolve; }) : json({message:'Expired'},401);
  try {
    const pending = api.refreshCsrf();
    const rejected = assert.rejects(pending, error => error.code === 'AUTH_SESSION_CHANGED');
    await new Promise(resolve => setImmediate(resolve));
    await assert.rejects(api.request('/applications'), error => error.status === 401);
    finishCsrf(json(csrf));
    await rejected;
  } finally { if(previousWindow === undefined) delete globalThis.window; else globalThis.window=previousWindow; }
});

test('intentional auth actions use the origin Web Lock when it is available', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const locks = [];
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{locks:{request(name, callback) { locks.push(name); return callback(); }}}});
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : json({status:'VERIFICATION_REQUEST_ACCEPTED',message:'Check your email.'},202);
  try {
    await api.authApi.resendVerification('user@example.test');
    assert.deepEqual(locks,['campus-auth-transition']);
  } finally { if(descriptor) Object.defineProperty(globalThis,'navigator',descriptor); else delete globalThis.navigator; }
});

test('cross-tab session signals clear stale guards without broadcasting account data or secrets', async () => {
  assert.equal(typeof api.subscribeSessionChanges,'function');
  const previousWindow = globalThis.window;
  const previousChannel = globalThis.BroadcastChannel;
  const channels = [];
  class Channel {
    constructor() { channels.push(this); this.sent=[]; }
    postMessage(data) { this.sent.push(data); }
    close() {}
  }
  globalThis.window = new EventTarget();
  globalThis.BroadcastChannel = Channel;
  let signals=0;
  const unsubscribe = api.subscribeSessionChanges(() => signals++);
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : json({id:'new',displayName:'New',email:'new@example.test'});
  try {
    await api.authApi.login('new@example.test','long enough password');
    assert.deepEqual(channels[0].sent,['session-changed']);
    const guard=api.sessionGuard();
    channels[0].onmessage({data:'session-changed'});
    assert.equal(guard(),false);
    assert.equal(signals,1);
  } finally {
    unsubscribe();
    if(previousWindow === undefined) delete globalThis.window; else globalThis.window=previousWindow;
    globalThis.BroadcastChannel=previousChannel;
  }
});

test('login and logout cannot overlap session-creating requests', async () => {
  const calls = [];
  let finishLogin;
  let signalLogin;
  const started = new Promise(resolve => { signalLogin = resolve; });
  globalThis.fetch = async url => {
    calls.push(url);
    if (url === '/api/auth/csrf') return json(csrf);
    if (url === '/api/auth/login') return new Promise(resolve => { finishLogin = resolve; signalLogin(); });
    return new Response(null, {status:204});
  };
  const login = api.authApi.login('new@example.test', 'long enough password');
  await started;
  const logout = api.authApi.logout();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.includes('/api/auth/logout'), false);
  finishLogin(json({id:'new',displayName:'New',email:'new@example.test'}));
  await Promise.all([login, logout]);
  assert.ok(calls.indexOf('/api/auth/logout') > calls.lastIndexOf('/api/auth/csrf'));
});

test('a rejected auth action releases serialization for the next explicit action', async () => {
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : json({message:'Bad login'}, 401);
  await assert.rejects(api.authApi.login('new@example.test', 'wrong'), error => error.status === 401);
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : new Response(null, {status:204});
  await api.authApi.logout();
});

test('reset transport exposes typed same-origin routes and never replays a failed confirmation', async () => {
  const module = await import('../src/data/passwordResetApi.ts').catch(() => null);
  assert.ok(module?.passwordResetApi, 'Password-reset transport is missing');
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({url, options});
    if (url === '/api/auth/csrf') return json(csrf);
    if (url.endsWith('/request')) return json({status:'PASSWORD_RESET_REQUEST_ACCEPTED',message:'Check your inbox.'},202);
    return json({message:'Invalid link',code:'PASSWORD_RESET_INVALID'},400);
  };
  assert.equal((await module.passwordResetApi.requestReset(' user@example.test ')).status, 'PASSWORD_RESET_REQUEST_ACCEPTED');
  await assert.rejects(module.passwordResetApi.confirmReset(token, 'long enough password'), error => error.code === 'PASSWORD_RESET_INVALID');
  const writes = calls.filter(call => call.options.method === 'POST');
  assert.deepEqual(writes.map(call => call.url), ['/api/auth/password-reset/request','/api/auth/password-reset/confirm']);
  assert.deepEqual(JSON.parse(writes[0].options.body), {email:'user@example.test'});
  assert.deepEqual(JSON.parse(writes[1].options.body), {token, newPassword:'long enough password'});
  for (const call of writes) {
    assert.equal(call.options.credentials,'same-origin');
    assert.equal(call.options.headers.get('X-CSRF-TOKEN'),'fresh-token');
  }
  let attempts = 0;
  globalThis.fetch = async url => { if (url === '/api/auth/csrf') return json(csrf); attempts++; throw new Error('Lost response'); };
  await assert.rejects(module.passwordResetApi.confirmReset(token,'long enough password'), error => error.status === 0);
  assert.equal(attempts,1);
});

function browserFixture(hash) {
  const listeners = new Set();
  const location = {hash, pathname:'/', search:'?theme=dark'};
  const replacements = [];
  return {location, replacements,
    history: {state:{existing:true}, replaceState(state,_title,url) {
      replacements.push({state,url});
      this.state=state;
      if(url!==undefined) location.hash=new URL(url,'https://example.test').hash;
    }},
    addEventListener(_event,listener) { listeners.add(listener); },
    removeEventListener(_event,listener) { listeners.delete(listener); },
    navigate(hash) { location.hash=hash; listeners.forEach(listener => listener()); },
  };
}

test('reset fragments are scrubbed immediately and canonical tokens survive StrictMode subscription cycles', async () => {
  const module = await import('../src/domain/passwordResetLink.ts').catch(() => null);
  assert.ok(module?.createPasswordResetLinkStore, 'Password-reset link capture is missing');
  const browser = browserFixture('#reset-password=' + token);
  const store = module.createPasswordResetLinkStore(browser);
  const snapshot = store.getSnapshot();
  assert.deepEqual(snapshot,{token});
  assert.equal(browser.location.hash,'');
  assert.equal(browser.replacements.length,1);
  assert.equal(browser.replacements[0].url,'/?theme=dark');
  assert.equal(browser.history.state.existing,true);
  assert.equal(JSON.stringify(browser.history.state).includes(token),false);
  store.subscribe(() => {})();
  const unsubscribe = store.subscribe(() => {});
  assert.equal(store.getSnapshot(),snapshot);
  browser.navigate('#reset-password=' + 'b'.repeat(42) + 'Q');
  assert.deepEqual(store.getSnapshot(),{token:'b'.repeat(42)+'Q'});
  store.dismiss();
  assert.equal(store.getSnapshot(),null);
  unsubscribe();
});

test('reloading a scrubbed reset link asks to reopen the email without retaining its token', async () => {
  const {createPasswordResetLinkStore}=await import('../src/domain/passwordResetLink.ts');
  const browser=browserFixture('#reset-password='+token);
  createPasswordResetLinkStore(browser);
  const reloaded=createPasswordResetLinkStore(browser);
  assert.deepEqual(reloaded.getSnapshot(),{token:null});
  assert.equal(browser.location.hash,'');
  assert.equal(browser.history.state.existing,true);
  assert.equal(JSON.stringify(browser.history.state).includes(token),false);
});

test('dismissing or completing reset recovery clears its reload hint and preserves unrelated history state', async () => {
  const {createPasswordResetLinkStore}=await import('../src/domain/passwordResetLink.ts');
  for(const action of ['dismiss','complete']) {
    const browser=browserFixture('#reset-password='+token);
    const store=createPasswordResetLinkStore(browser);
    store[action]();
    assert.deepEqual(browser.history.state,{existing:true});
    assert.equal(createPasswordResetLinkStore(browser).getSnapshot(),null);
  }
});

test('a fresh verification link replaces the reset reload hint regardless of store initialization order', async () => {
  const {createPasswordResetLinkStore,connectAuthenticationLinks}=await import('../src/domain/passwordResetLink.ts');
  const {createVerificationLinkStore}=await import('../src/domain/verificationLink.ts');
  for(const verificationFirst of [false,true]) {
    const browser=browserFixture('#reset-password='+token);
    createPasswordResetLinkStore(browser);
    browser.location.hash='#verify-email='+'b'.repeat(43);
    let reset;
    let verification;
    if(verificationFirst) {
      verification=createVerificationLinkStore(browser);
      reset=createPasswordResetLinkStore(browser);
    } else {
      reset=createPasswordResetLinkStore(browser);
      verification=createVerificationLinkStore(browser);
    }
    const disconnect=connectAuthenticationLinks(reset,verification);
    try {
      assert.equal(reset.getSnapshot(),null);
      assert.deepEqual(verification.getSnapshot(),{token:'b'.repeat(43)});
      assert.deepEqual(browser.history.state,{existing:true});
      assert.equal(createPasswordResetLinkStore(browser).getSnapshot(),null);
    } finally { disconnect(); }
  }
});

test('completion erases the captured secret while keeping the success view in memory', async () => {
  const module=await import('../src/domain/passwordResetLink.ts');
  const store=module.createPasswordResetLinkStore(browserFixture('#reset-password='+token));
  store.complete();
  assert.deepEqual(store.getSnapshot(),{token:null,completed:true});
});

test('opening another authentication link switches views without losing the new token', async () => {
  const resetModule=await import('../src/domain/passwordResetLink.ts');
  const {createVerificationLinkStore}=await import('../src/domain/verificationLink.ts');
  assert.equal(typeof resetModule.connectAuthenticationLinks,'function');
  const browser=browserFixture('');
  const reset=resetModule.createPasswordResetLinkStore(browser);
  const verification=createVerificationLinkStore(browser);
  const disconnect=resetModule.connectAuthenticationLinks(reset,verification);
  try {
    browser.navigate('#reset-password='+token);
    assert.deepEqual(reset.getSnapshot(),{token});
    browser.navigate('#verify-email='+'b'.repeat(43));
    assert.equal(reset.getSnapshot(),null);
    assert.deepEqual(verification.getSnapshot(),{token:'b'.repeat(43)});
    browser.navigate('#reset-password='+token);
    assert.equal(verification.getSnapshot(),null);
    assert.deepEqual(reset.getSnapshot(),{token});
  } finally { disconnect(); }
});

test('a current CSRF rejection clears only cached CSRF and waits for the next explicit submission', async () => {
  const calls=[];
  globalThis.fetch=async url => { calls.push(url); return url==='/api/auth/csrf' ? json(csrf) : json({message:'Refresh your token'},403); };
  await api.refreshCsrf();
  await assert.rejects(api.request('/applications',{method:'POST'}), error => error.status===403);
  assert.deepEqual(calls,['/api/auth/csrf','/api/applications']);
  globalThis.fetch=async url => { calls.push(url); return json(url==='/api/auth/csrf' ? csrf : {}); };
  await api.request('/applications',{method:'POST'});
  assert.deepEqual(calls,['/api/auth/csrf','/api/applications','/api/auth/csrf','/api/applications']);
});

test('session sync falls back to token-free storage events if BroadcastChannel is blocked', async () => {
  const previousWindow=globalThis.window;
  const previousChannel=globalThis.BroadcastChannel;
  const browser=new EventTarget();
  const notifications=[];
  browser.localStorage={setItem(key,value) { notifications.push({key,value}); }};
  globalThis.window=browser;
  globalThis.BroadcastChannel=class { constructor() { throw new Error('Blocked'); } };
  let unsubscribe;
  try {
    unsubscribe=api.subscribeSessionChanges(() => {});
    globalThis.fetch=async url => url==='/api/auth/csrf' ? json(csrf) : new Response(null,{status:204});
    await api.authApi.logout();
    assert.equal(notifications.length,1);
    assert.equal(notifications[0].key,'campus:auth-session-changed');
    assert.match(notifications[0].value,/^[0-9a-f-]{36}$/);
  } finally {
    unsubscribe?.();
    if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;
    globalThis.BroadcastChannel=previousChannel;
  }
});

test('noncanonical and malformed reset fragments are scrubbed and cannot be submitted', async () => {
  const module = await import('../src/domain/passwordResetLink.ts').catch(() => null);
  assert.ok(module?.createPasswordResetLinkStore, 'Password-reset link capture is missing');
  for (const fragment of ['', 'short', 'a'.repeat(43), token+'=', token+'&other=1', token+'%20']) {
    const browser = browserFixture('#reset-password='+fragment);
    assert.deepEqual(module.createPasswordResetLinkStore(browser).getSnapshot(),{token:null});
    assert.equal(browser.location.hash,'');
  }
  const browser = browserFixture('#verify-email='+token);
  assert.equal(module.createPasswordResetLinkStore(browser).getSnapshot(),null);
  assert.equal(browser.location.hash,'#verify-email='+token);
});
