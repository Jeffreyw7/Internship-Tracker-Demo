/** Opt-in E2E against the running free local launcher. Creates only synthetic test data.
 * node tests/local-mailpit-smoke.mjs [--browser-request] [--pause-before-confirm]
 * Uses localhost:8080 + Mailpit:8025; never clears mail or restarts the user's services.
 */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const app='http://127.0.0.1:8080', inbox='http://127.0.0.1:8025';
const args=new Set(process.argv.slice(2));
assert.ok([...args].every(arg=>['--browser-request','--pause-before-confirm'].includes(arg)),'Unsupported argument');
assert.ok(!args.has('--pause-before-confirm')||process.stdin.isTTY,
  '--pause-before-confirm requires an interactive terminal; omit it for unattended testing');
const email=`reset-e2e-${randomUUID()}@example.test`;
const unknown=`unknown-e2e-${randomUUID()}@example.test`;
const oldPassword=`Original test ${randomUUID()}`;
const newPassword=`Changed test ${randomUUID()}`;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let checks=0;
function pass(name){checks++;console.log(`PASS: ${name}`);}

function client(){
  const cookies=new Map();
  async function call(path,{method='GET',data,headers={},form=false,status=200}={}){
    const response=await fetch(app+path,{method,redirect:'manual',signal:AbortSignal.timeout(10000),headers:{
      Cookie:[...cookies].map(([key,value])=>`${key}=${value}`).join('; '),
      ...(data===undefined?{}:{'Content-Type':form?'application/x-www-form-urlencoded':'application/json'}),...headers,
    },body:data===undefined?undefined:form?new URLSearchParams(data):JSON.stringify(data)});
    for(const value of response.headers.getSetCookie()){
      const pair=value.split(';',1)[0], index=pair.indexOf('=');
      cookies.set(pair.slice(0,index),pair.slice(index+1));
    }
    assert.equal(response.status,status,`${method} ${path}: unexpected status (response content redacted)`);
    const text=await response.text();
    return {body:text&&response.headers.get('content-type')?.includes('application/json')?JSON.parse(text):text,headers:response.headers};
  }
  async function write(path,data,{method='POST',status=200,form=false,csrf=true}={}){
    const headers={};
    if(csrf){const {body:token}=await call('/api/auth/csrf');headers[token.headerName]=token.token;}
    return call(path,{method,data,headers,status,form});
  }
  return {call,write};
}
async function messagesFor(recipient){
  const response=await fetch(inbox+'/api/v1/messages',{signal:AbortSignal.timeout(5000)});
  assert.equal(response.status,200,'Mailpit message listing unavailable');
  const listing=await response.json();
  return listing.messages.filter(message=>message.To?.some(to=>to.Address?.toLowerCase()===recipient));
}
async function mail(recipient,subject){
  const deadline=Date.now()+(args.has('--browser-request')?180000:30000);
  while(Date.now()<deadline){
    const listing=await messagesFor(recipient);
    const found=listing.find(message=>message.Subject===subject);
    if(found){
      const response=await fetch(inbox+'/api/v1/message/'+encodeURIComponent(found.ID),{signal:AbortSignal.timeout(5000)});
      assert.equal(response.status,200,'Mailpit message body unavailable');
      return {id:found.ID,...await response.json()};
    }
    await sleep(250);
  }
  throw Error(`Expected test message was not captured: ${subject}`);
}
function token(message,purpose){
  const match=(String(message.Text??'')+'\n'+String(message.HTML??'')).match(new RegExp(`#${purpose}=([A-Za-z0-9_-]{43})`));
  assert.ok(match,'Expected test link is missing from message');
  const links=String(message.Text??'').match(/https?:\/\/[^\s<>"']+/g)??[];
  const link=links.map(value=>new URL(value)).find(value=>value.hash===`#${purpose}=${match[1]}`);
  assert.ok(link,'Expected test link is missing from the plain-text message');
  assert.equal(link.origin,app,'Email must point to the running local app');
  assert.equal(link.pathname,'/','Email must open the authentication shell');
  return match[1];
}
async function pause(){
  console.log('PAUSED: inspect the synthetic link, then send Enter to continue HTTP confirmation.');
  await new Promise((resolve,reject)=>{
    const timeout=setTimeout(()=>{process.stdin.pause();reject(Error('Browser inspection timed out'));},180000);
    process.stdin.once('data',()=>{clearTimeout(timeout);process.stdin.pause();resolve();});
    process.stdin.resume();
  });
}

try{
  const anonymous=client(), first=client(), existing=client(), recovered=client();
  assert.equal((await anonymous.call('/api/health')).body.application,'campus-tracker');
  await anonymous.write('/api/auth/register',{email,password:oldPassword,displayName:'Password Reset E2E',includeSamples:false},{status:202});
  const verification=await mail(email,'Verify your Campus Internship Tracker email');
  await anonymous.write('/api/auth/email-verification/confirm',{token:token(verification,'verify-email'),password:oldPassword});
  const login=await first.write('/api/auth/login',{email,password:oldPassword},{form:true});
  await existing.write('/api/auth/login',{email,password:oldPassword},{form:true});
  pass('synthetic account verified through real Mailpit and two sessions signed in');
  const draft={company:'Password Reset E2E',role:'Local test',location:'Local',status:'Applied',
    appliedDate:new Date().toISOString().slice(0,10),deadline:null,followUpDate:null,notes:'Must survive reset',stages:[]};
  const application=(await first.write('/api/applications',draft,{status:201})).body;
  await first.write('/api/me/preferences',{themePreference:'dark'},{method:'PUT'});
  await first.write('/api/auth/logout',{} ,{status:204});
  const unknownAcceptance=await anonymous.write('/api/auth/password-reset/request',{email:unknown},{status:202});
  assert.match(unknownAcceptance.headers.get('cache-control'),/(?:^|,)\s*no-store\s*(?:,|$)/i);
  if(args.has('--browser-request'))console.log('BROWSER_REQUEST '+JSON.stringify({email,origin:app}));
  else{
    const accepted=await anonymous.write('/api/auth/password-reset/request',{email},{status:202});
    assert.deepEqual(accepted.body,unknownAcceptance.body,'Known and unknown acceptance must match');
  }
  const resetMessage=await mail(email,'Reset your Campus Internship Tracker password');
  const resetToken=token(resetMessage,'reset-password');
  assert.equal((await messagesFor(unknown)).length,0,'Unknown account must not get recovery mail');
  pass('reset email captured for the test account; unknown address remains private');
  if(args.has('--pause-before-confirm')){
    console.log('BROWSER_LINK '+JSON.stringify({email,url:app+'/#reset-password='+resetToken,mailpitMessage:resetMessage.id}));
    await pause();
  }
  await anonymous.call('/');
  assert.equal((await existing.call('/api/auth/me')).body.id,login.body.id);
  await anonymous.write('/api/auth/password-reset/confirm',{token:resetToken,newPassword},{csrf:false,status:403});
  await anonymous.write('/api/auth/password-reset/confirm',{token:resetToken,newPassword:'short'},{status:400});
  assert.equal((await existing.call('/api/auth/me')).body.id,login.body.id);
  pass('page GET and rejected CSRF/password submissions preserve the existing session');
  const completed=await anonymous.write('/api/auth/password-reset/confirm',{token:resetToken,newPassword});
  assert.equal(completed.body.status,'PASSWORD_RESET_COMPLETE');
  const revoked=await existing.call('/api/auth/me',{status:401});
  assert.equal(revoked.headers.get('set-cookie'),null,'Passive rejection must not delete a newer cookie');
  pass('confirmation succeeds and pre-reset authentication is revoked');
  const replay=await anonymous.write('/api/auth/password-reset/confirm',{token:resetToken,newPassword:oldPassword},{status:400});
  assert.equal(replay.body.code,'PASSWORD_RESET_INVALID');
  const invalid=await anonymous.write('/api/auth/password-reset/confirm',{token:'A'.repeat(43),newPassword},{status:400});
  assert.equal(invalid.body.code,'PASSWORD_RESET_INVALID');
  pass('used and unknown canonical tokens are rejected');
  await recovered.write('/api/auth/login',{email,password:oldPassword},{form:true,status:401});
  assert.equal((await recovered.write('/api/auth/login',{email,password:newPassword},{form:true})).body.id,login.body.id);
  assert.equal((await recovered.call('/api/applications/'+application.id)).body.notes,draft.notes);
  assert.equal((await recovered.call('/api/me/preferences')).body.themePreference,'dark');
  pass('old password fails; new password opens the same account with application and theme intact');
  const notice=await mail(email,'Your Campus Internship Tracker password changed');
  const noticeBody=String(notice.Text??'')+'\n'+String(notice.HTML??'');
  for(const secret of [oldPassword,newPassword,resetToken])assert.ok(!noticeBody.includes(secret),'Notification must not contain credentials or reset token');
  pass('password-change notification reaches Mailpit without passwords or recovery token');
  await recovered.write('/api/auth/logout',{}, {status:204});
  console.log(JSON.stringify({result:'PASS',checks,testAccount:email,scope:'running localhost app + actual Mailpit; synthetic data retained'},null,2));
}catch(error){
  console.error('FAIL: '+error.message);
  process.exitCode=1;
}
