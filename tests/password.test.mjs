import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readPasswordLink } from '../src/domain/passwordLink.ts';
import { passwordApi, refreshCsrf, ApiError } from '../src/data/api.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
test('captures valid password fragment and removes it without sending a request',()=>{
  let replacement;
  const token='a'.repeat(43);
  const link=readPasswordLink({hash:'#password-reset='+token,pathname:'/',search:'?test=1'},
    {replaceState:(_state,_title,url)=>{replacement=url;}});
  assert.deepEqual(link,{purpose:'reset',token});assert.equal(replacement,'/?test=1');
});
test('initial link capture releases its token when the flow is dismissed',async()=>{
  const previousWindow=globalThis.window;
  const token='c'.repeat(43);
  globalThis.window={location:{hash:'#password-reset='+token,pathname:'/',search:''},history:{replaceState(){}}};
  try {
    const links=await import('../src/domain/passwordLink.ts?initial-clear');
    assert.equal(links.initialPasswordLink.token,token);
    assert.equal(typeof links.clearInitialPasswordLink,'function');
    links.clearInitialPasswordLink();
    assert.equal(links.initialPasswordLink,null);
  } finally {
    if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;
  }
});
test('invalid and ambiguous password fragments cannot supply a usable token',()=>{
  for(const hash of ['#password-change=bad','#password-reset='+ 'a'.repeat(43)+'&password-change='+ 'b'.repeat(43)]){
    const link=readPasswordLink({hash,pathname:'/',search:''},{replaceState(){}});
    assert.equal(link.token,null);
  }
});
test('password writes use explicit endpoints and CSRF without retry',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options)=>{
    if(url==='/api/auth/csrf')return json({token:'csrf',headerName:'X-CSRF-TOKEN'});
    calls.push({url,options});return json({code:'PASSWORD_CHALLENGE_INVALID',message:'Expired'},400);
  };
  await refreshCsrf();
  await assert.rejects(()=>passwordApi.confirmReset('a'.repeat(43),'Campus-new-123!'),e=>e instanceof ApiError&&e.code==='PASSWORD_CHALLENGE_INVALID');
  assert.equal(calls.length,1);assert.equal(calls[0].url,'/api/auth/password-reset/confirm');
  assert.equal(calls[0].options.headers.get('X-CSRF-TOKEN'),'csrf');
  assert.deepEqual(JSON.parse(calls[0].options.body),{token:'a'.repeat(43),newPassword:'Campus-new-123!'});
});
test('change requests transmit only the current password',async()=>{
  let payload;
  globalThis.fetch=async(url,options)=>url==='/api/auth/csrf'?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):
    (payload={url,body:JSON.parse(options.body)},json({status:'PASSWORD_CHANGE_REQUEST_ACCEPTED',message:'Check email'}));
  await refreshCsrf();await passwordApi.requestChange('Campus-old-123!');
  assert.deepEqual(payload,{url:'/api/auth/password-change/request',body:{currentPassword:'Campus-old-123!'}});
});
test('lost confirmation response discards CSRF before a later recovery request',async()=>{
  let reads=0,writes=0;
  globalThis.fetch=async(url)=>{
    if(url==='/api/auth/csrf'){reads++;return json({token:'csrf-'+reads,headerName:'X-CSRF-TOKEN'});}
    writes++;if(url.endsWith('/confirm'))throw new Error('Response lost after commit');
    return json({status:'PASSWORD_RESET_REQUEST_ACCEPTED',message:'Check email'});
  };
  await refreshCsrf();
  await assert.rejects(()=>passwordApi.confirmReset('a'.repeat(43),'Campus-new-123!'));
  await passwordApi.requestReset('test@example.test');
  assert.equal(reads,2);assert.equal(writes,2);
});
