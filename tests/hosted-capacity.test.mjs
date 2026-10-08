import assert from 'node:assert/strict';
import test from 'node:test';
import {createServer} from 'node:http';
import {buildOptions,validateTarget,validateFixture,safeSummary,acceptCapacity} from './load/hosted/config.mjs';

const phase={durationSeconds:2,foregroundRate:5,preferencesRate:2,authRate:1,foregroundVUs:2,preferencesVUs:1,authVUs:1};
export function target(){return {
  kind:'campus-hosted-capacity-v1',runId:'11111111-1111-4111-8111-111111111111',base:'http://127.0.0.1:12345',disposable:true,rehearsal:true,targetsApproved:false,
  today:'2026-10-08',phases:{rehearsal:phase},dataset:{accounts:8,applications:5353,stages:16059,largestHistory:5003,observedAt:'2026-10-08T12:00:00Z',evidenceId:'inventory-001'},
  deployment:{imageDigest:`sha256:${'a'.repeat(64)}`,resourcesId:'resources-001',monthlyCostUsd:0},
};}
export function fixture(plan=target()){return {kind:'campus-hosted-sessions-v1',runId:plan.runId,base:plan.base,
  authIdentities:[{email:'load-5@example.test',password:'Synthetic-private-123!'},{email:'load-6@example.test',password:'Synthetic-private-123!'}],accounts:Array.from({length:4},(_,i)=>({
  email:`load-${i+1}@example.test`,password:'Synthetic-private-123!',cookie:`JSESSIONID=private-session-${i}`,
  csrfHeader:'X-CSRF-TOKEN',csrfToken:`private-csrf-${i}`,applicationId:`22222222-2222-4222-8222-${String(i+1).padStart(12,'0')}`,
}))};}
export function metrics(plan=target(),name='rehearsal'){
  const p=plan.phases[name],count=n=>({values:{count:n}}),latency={values:{'p(95)':20,'p(99)':30,count:10}};
  return {metrics:{capacity_requests:count((p.foregroundRate+p.preferencesRate+p.authRate*4)*p.durationSeconds),
    foreground_iterations:count(p.foregroundRate*p.durationSeconds),preferences_iterations:count(p.preferencesRate*p.durationSeconds),
    auth_iterations:count(p.authRate*p.durationSeconds),auth_completed:count(p.authRate*p.durationSeconds),unexpected_status:{values:{rate:0}},
    dropped_iterations:count(0),password_latency:{values:{...latency.values,count:p.authRate*p.durationSeconds}},
    ...Object.fromEntries(['list','summary','activities','detail','update','preferences'].map(route=>[`route_requests{route:${route}}`,count(10)])),
    ...Object.fromEntries(['list','summary','activities','detail','update','preferences'].map(route=>[`api_latency{route:${route}}`,latency])),
  }};
}

test('arrival rates count all four successful login/logout requests and bound preallocated clients',()=>{
  const plan=target(),options=buildOptions(plan,'rehearsal');
  assert.equal(options.scenarios.foreground.executor,'constant-arrival-rate');
  assert.equal(options.scenarios.auth.rate,1);assert.equal(options.scenarios.preferences.rate,2);
  assert.equal(options.scenarios.foreground.preAllocatedVUs,2);assert.equal(options.scenarios.foreground.maxVUs,2);
  assert.deepEqual(options.thresholds.capacity_requests,['count>=22']);
  assert.deepEqual(options.thresholds.dropped_iterations,['count==0']);
  assert.equal(options.maxRedirects,0);assert.equal(options.insecureSkipTLSVerify,false);
  assert.ok(!options.systemTags.includes('url'),'Raw URLs must never be metric tags.');
});

test('target and private sessions reject unsafe origins, unrelated runs, account aliases and insufficient isolated mutation slots',()=>{
  const plan=target();validateTarget(plan);validateFixture(fixture(plan),plan,'rehearsal');
  for(const base of ['http://capacity.example.com','https://u:password@capacity.example.com','https://capacity.example.com/path','https://capacity.example.com?token=secret']){
    assert.throws(()=>validateTarget({...plan,base}),/origin|HTTPS/);
  }
  assert.throws(()=>validateTarget({...plan,disposable:false}),/disposable/);
  assert.throws(()=>validateFixture({...fixture(plan),runId:'other'},plan,'rehearsal'),/run/);
  assert.throws(()=>validateFixture({...fixture(plan),accounts:fixture(plan).accounts.slice(0,1)},plan,'rehearsal'),/slots/);
  const duplicate=fixture(plan);duplicate.accounts[1].applicationId=duplicate.accounts[0].applicationId;
  assert.throws(()=>validateFixture(duplicate,plan,'rehearsal'),/distinct/);
  const alias=fixture(plan);alias.accounts[0].email='real-user@example.com';
  assert.throws(()=>validateFixture(alias,plan,'rehearsal'),/synthetic/);
  assert.throws(()=>validateTarget({...plan,phases:{rehearsal:{...phase,durationSeconds:Infinity}}}),/duration/);
});

test('capacity verdict fails incomplete traffic, successful auth, drops, conflicts and route-tail regressions',()=>{
  const plan=target();assert.equal(safeSummary(plan,'rehearsal',metrics()).workloadPassed,true);
  for(const [name,values] of [
    ['capacity_requests',{count:21}],['auth_completed',{count:1}],['dropped_iterations',{count:1}],
    ['unexpected_status',{rate:.01}],['api_latency{route:summary}',{'p(95)':301,'p(99)':30,count:10}],
    ['password_latency',{'p(95)':2001,count:10}],
  ]){
    const data=metrics();data.metrics[name]={values};
    assert.equal(safeSummary(plan,'rehearsal',data).workloadPassed,false,name);
  }
  const missing=metrics();delete missing.metrics['api_latency{route:update}'];
  assert.equal(safeSummary(plan,'rehearsal',missing).workloadPassed,false,'An unexercised route cannot pass.');
});

test('summary allowlists measurements and never promotes a rehearsal or leaks fixture/extension secrets',()=>{
  const plan={...target(),password:'never-report',deployment:{...target().deployment,secret:'never-report'}};
  const data=metrics();data.metrics['http_req_duration{url:https://private?token=never-report}']={values:{max:123}};
  data.metrics.capacity_requests.values.secret='never-report';data.extra='never-report';
  const result=safeSummary(plan,'rehearsal',data);
  assert.equal(result.mode,'rehearsal');assert.equal(acceptCapacity([result]).accepted,false);
  assert.doesNotMatch(JSON.stringify(result),/never-report|private-session|private-csrf/);
});

test('hosted acceptance requires all target phases, approved targets, representative observed inventory and same deployment',()=>{
  const plan=target();plan.base='https://capacity.example.test';plan.rehearsal=false;plan.targetsApproved=true;
  plan.dataset={...plan.dataset,accounts:300000,applications:60000000,stages:180000000,largestHistory:10000};
  plan.phases={
    sustained:{...phase,durationSeconds:1800,foregroundRate:700,preferencesRate:400,authRate:100},
    burst:{...phase,durationSeconds:300,foregroundRate:1000,preferencesRate:600,authRate:100},
    soak:{...phase,durationSeconds:14400,foregroundRate:700,preferencesRate:400,authRate:100},
  };
  const reports=Object.keys(plan.phases).map(name=>safeSummary(plan,name,metrics(plan,name)));
  assert.equal(acceptCapacity(reports).accepted,true);
  assert.equal(acceptCapacity(reports.slice(0,2)).accepted,false);
  assert.equal(acceptCapacity(reports.map(r=>({...r,targetsApproved:false}))).accepted,false);
  assert.equal(acceptCapacity(reports.map(r=>({...r,dataset:{...r.dataset,applications:5003}}))).accepted,false);
  assert.equal(acceptCapacity(reports.map((r,i)=>i===2?{...r,deployment:{...r.deployment,imageDigest:`sha256:${'b'.repeat(64)}`}}:r)).accepted,false);
});

test('session preparation validates the entire synthetic target before HTTP and captures real cookie/rotated CSRF without writes',async()=>{
  const {prepareFixture}=await import('./load/hosted/prepare.mjs');
  const paths=[];let token=0;
  const server=createServer(async(request,response)=>{
    paths.push(`${request.method} ${request.url}`);response.setHeader('Content-Type','application/json');
    if(request.url==='/api/auth/csrf'){response.setHeader('Set-Cookie','JSESSIONID=synthetic-session; HttpOnly');response.end(JSON.stringify({token:`rotated-${++token}`,headerName:'X-CSRF-TOKEN'}));}
    else if(request.url==='/api/auth/login')response.end('{}');
    else response.end(JSON.stringify({id:request.url.split('/').pop(),version:0,stages:[]}));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const plan=target();plan.base=`http://127.0.0.1:${server.address().port}`;
    const identities={kind:'campus-hosted-identities-v1',runId:plan.runId,base:plan.base,accounts:fixture(plan).accounts.map(({email,password,applicationId})=>({email,password,applicationId}))};
    identities.authIdentities=fixture(plan).authIdentities;
    await assert.rejects(prepareFixture({...plan,disposable:false},identities,{delayMs:0}),/disposable/);
    assert.equal(paths.length,0);
    const sessions=await prepareFixture(plan,identities,{delayMs:0});
    assert.equal(sessions.accounts[0].cookie,'JSESSIONID=synthetic-session');assert.equal(sessions.accounts[0].csrfToken,'rotated-2');
    assert.equal(paths.length,16);assert.ok(paths.every(p=>p.startsWith('GET ')||p==='POST /api/auth/login'));
    validateFixture(sessions,plan,'rehearsal');
  }finally{await new Promise(resolve=>server.close(resolve));}
});

test('successful auth uses an independent rotating identity pool sized for the unchanged email quota',()=>{
  const plan=target(),privateFixture=fixture(plan);
  assert.throws(()=>validateFixture({...privateFixture,authIdentities:[]},plan,'rehearsal'),/auth.*pool/);
  assert.throws(()=>validateFixture({...privateFixture,authIdentities:[privateFixture.accounts[0]]},plan,'rehearsal'),/distinct/);
  plan.rehearsal=false;plan.base='https://capacity.example.test';plan.phases.rehearsal.authRate=100;
  privateFixture.base=plan.base;
  assert.throws(()=>validateFixture(privateFixture,plan,'rehearsal'),/6000/);
});

test('hosted acceptance cannot replace foreground application traffic with cheap polling',()=>{
  const plan=target();plan.base='https://capacity.example.test';plan.rehearsal=false;plan.targetsApproved=true;
  plan.dataset={...plan.dataset,accounts:300000,applications:60000000,stages:180000000,largestHistory:10000};
  plan.phases={sustained:{...phase,durationSeconds:1800,foregroundRate:1,preferencesRate:1099,authRate:100},
    burst:{...phase,durationSeconds:300,foregroundRate:1,preferencesRate:1599,authRate:100},
    soak:{...phase,durationSeconds:14400,foregroundRate:1,preferencesRate:1099,authRate:100}};
  const reports=Object.keys(plan.phases).map(name=>safeSummary(plan,name,metrics(plan,name)));
  assert.ok(reports.every(r=>r.workloadPassed));assert.equal(acceptCapacity(reports).accepted,false);
});

test('functional rehearsal keeps failed capacity gates but requires every claimed API route and actual latency samples',async()=>{
  const {validateRehearsal}=await import('./load/hosted/config.mjs');
  assert.equal(typeof validateRehearsal,'function');
  const plan=target(),data=metrics();data.metrics.dropped_iterations.values.count=1;
  const report=safeSummary(plan,'rehearsal',data);assert.equal(report.workloadPassed,false);
  validateRehearsal(report,99);
  assert.throws(()=>validateRehearsal(report,0),/exit/);
  for(const route of ['list','summary','activities','detail','update','preferences']){
    const missing=structuredClone(report);missing.metrics[`route_requests{route:${route}}`].values.count=0;
    assert.throws(()=>validateRehearsal(missing,99),/coverage/);
    const unsampled=structuredClone(report);unsampled.metrics[`api_latency{route:${route}}`].values.count=0;
    assert.throws(()=>validateRehearsal(unsampled,99),/samples/);
  }
});
