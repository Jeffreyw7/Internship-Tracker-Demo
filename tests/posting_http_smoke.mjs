// Self-contained HTTP/restart test. Uses only synthetic accounts and a temporary database.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawn,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
fs.mkdirSync(path.join(root,'test-results/job-posting'),{recursive:true});
const temp=fs.mkdtempSync(path.join(root,'test-results/job-posting/http-'));
const javaHome=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8',windowsHide:true}).stderr.match(/java\.home\s*=\s*(.+)/)?.[1]?.trim();
assert.ok(javaHome,'Java runtime is available');
const java=path.join(javaHome,'bin',process.platform==='win32'?'java.exe':'java');
const jar=path.join(root,'backend/target/campus-tracker-2.0.0.jar');assert.ok(fs.existsSync(jar),'Build with-ui first');
const probe=net.createServer();await new Promise(resolve=>probe.listen(0,'127.0.0.1',resolve));const port=probe.address().port;await new Promise(resolve=>probe.close(resolve));
const origin=`http://127.0.0.1:${port}`;
let server;
async function stop(){if(!server||server.exitCode!==null)return;const exit=new Promise(resolve=>server.once('exit',resolve));server.kill();await exit;}
async function start(){
  const pg=process.env.POSTING_SMOKE_POSTGRES_URL;
  const args=['-jar',jar,`--server.port=${port}`,'--server.address=127.0.0.1','--campus.registration-enabled=true','--server.servlet.session.cookie.secure=false',
    '--campus.reminders.worker-enabled=false','--campus.password.mail-enabled=false',
    `--spring.datasource.url=${pg??`jdbc:h2:file:${temp.replaceAll('\\','/')}/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`}`,
    `--spring.datasource.username=${pg?process.env.POSTING_TEST_POSTGRES_USER:'sa'}`,'--spring.datasource.password='];
  if(pg)args.push('--spring.profiles.active=prod');
  server=spawn(java,args,{cwd:temp,windowsHide:true,stdio:['ignore','pipe','pipe']});
  for(const stream of [server.stdout,server.stderr])stream.on('data',bytes=>fs.appendFileSync(path.join(temp,'server.log'),bytes));
  for(let i=0;i<160;i++){
    if(server.exitCode!==null)throw new Error(`Server exited. See ${temp}/server.log`);
    try{const r=await fetch(origin+'/api/health',{signal:AbortSignal.timeout(1000)});if(r.ok)return;}catch{}
    await new Promise(resolve=>setTimeout(resolve,250));
  }throw new Error('Server startup timed out');
}
class Client {
  cookie='';
  async http(route,method='GET',value,expected=200,version){
    const headers={Accept:'application/json',Cookie:this.cookie};
    if(method!=='GET'){const token=await this.http('/auth/csrf');headers[token.headerName]=token.token;headers.Cookie=this.cookie;}
    if(version!==undefined)headers['If-Match']=String(version);
    if(value!==undefined)headers['Content-Type']=value instanceof URLSearchParams?'application/x-www-form-urlencoded':'application/json';
    const response=await fetch(origin+'/api'+route,{method,headers,body:value===undefined?undefined:value instanceof URLSearchParams||typeof value==='string'?value:JSON.stringify(value),signal:AbortSignal.timeout(10000)});
    for(const c of response.headers.getSetCookie())if(c.startsWith('JSESSIONID='))this.cookie=c.split(';')[0];
    const data=await response.json();assert.equal(response.status,expected,`${method} ${route}: ${JSON.stringify(data)}`);return data;
  }
  login(account){return this.http('/auth/login','POST',new URLSearchParams({email:account.email,password:account.password}));}
}
try{
  await start();
  const account={email:`posting-${Date.now()}@example.test`,password:'Campus-posting-123!',displayName:'Posting Preview',includeSamples:false};
  const client=new Client();await client.http('/auth/register','POST',account,201);await client.login(account);
  const postingDetails={postingUrl:'https://example.test/gone',recruiterName:'Alex Chen',recruiterRole:'Recruiter',recruiterEmail:'alex@example.test',
    recruiterPhone:'+1 212 555 0100',recruiterProfileUrl:'',referralChannel:'EMPLOYEE',referralSource:'Engineering team',referrerName:'Sam',referrerContact:'sam@example.test',
    compensationMin:'25.10',compensationMax:'35.50',compensationCurrency:'USD',compensationPeriod:'HOUR',compensationStatus:'PAID',compensationOriginalText:'Housing included',
    descriptionSnapshot:'Original Java internship\n<script>This is literal text</script>\nUnicode café 😀'};
  const draft={company:'Posting Preview Labs',role:'Java Intern',location:'Remote',status:'Saved',appliedDate:null,deadline:null,followUpDate:null,notes:'Synthetic archive verification',stages:[],postingDetails};
  let app=await client.http('/applications','POST',draft,201);const route='/applications/'+app.id;
  const original=await client.http(route+'/posting-description');assert.equal(original.snapshot.descriptionText,postingDetails.descriptionSnapshot);
  // Exercise both write routes through the real JSON/controller boundary, without timing-based assertions.
  await client.http('/applications','POST',{...draft,postingDetails:{...postingDetails,compensationMin:'0'.repeat(60)+'25.10'}},400);
  const oversized=await client.http(route,'PUT',{...draft,postingDetails:{...postingDetails,compensationMax:'9'.repeat(1000000)}},400,app.version);
  assert.match(oversized.message,/64 characters/);
  for(const field of ['compensationMin','compensationMax'])for(const literal of ['25.10000000000000000001','-1e-1000']){
    const input=JSON.stringify({...draft,postingDetails:{...postingDetails,compensationMin:null,compensationMax:null,[field]:'raw-amount'}})
      .replace('"raw-amount"',literal);
    await client.http('/applications','POST',input,400);
    await client.http(route,'PUT',input,400,app.version);
  }
  const afterRejected=await client.http(route);assert.deepEqual(afterRejected,app);
  const oldVersion=app.version;postingDetails.descriptionSnapshot='Updated Java internship description';
  app=await client.http(route,'PUT',draft,200,app.version);
  await client.http(route,'PUT',draft,409,oldVersion);
  const legacy={...draft};delete legacy.postingDetails;app=await client.http(route,'PUT',legacy,200,app.version);
  assert.equal(app.postingDetails.recruiterName,'Alex Chen');assert.equal(app.postingDetails.descriptionRevision,2);
  const deleteResult=await client.http(route,'DELETE',undefined,200,app.version);
  await client.http(route+'/posting-description','GET',undefined,404);
  app=await client.http(route+'/restore','POST',undefined,200,deleteResult.version);
  await stop();await start();client.cookie='';await client.login(account);
  const saved=await client.http(route);assert.deepEqual(saved.postingDetails,app.postingDetails);
  const current=await client.http(route+'/posting-description');assert.equal(current.snapshot.descriptionText,postingDetails.descriptionSnapshot);
  const history=await client.http(route+'/posting-descriptions?size=1&page=1');assert.equal(history.totalElements,2);assert.equal(history.content[0].id,original.snapshot.id);
  const archived=await client.http(route+'/posting-descriptions/'+original.snapshot.id);assert.equal(archived.descriptionText,original.snapshot.descriptionText);
  const other=new Client();const otherAccount={...account,email:`other-${Date.now()}@example.test`};await other.http('/auth/register','POST',otherAccount,201);await other.login(otherAccount);
  await other.http(route+'/posting-descriptions','GET',undefined,404);
  fs.writeFileSync(path.join(root,process.argv.includes('--serve')?'test-results/job-posting/browser-account.json':'test-results/job-posting/http-account.json'),JSON.stringify({origin,...account,applicationId:app.id},null,2));
  console.log(`Posting HTTP smoke passed (${process.env.POSTING_SMOKE_POSTGRES_URL?'PostgreSQL':'file-backed H2'}): real CSRF/auth, bounded and exact compensation validation, version conflicts, history, legacy update, delete/undo, backend restart, and ownership isolation.`);
  if(process.argv.includes('--serve')){
    console.log('Synthetic browser server ready at '+origin);
    await new Promise(resolve=>{process.once('SIGINT',resolve);process.once('SIGTERM',resolve);});
  }
}finally{await stop();}
