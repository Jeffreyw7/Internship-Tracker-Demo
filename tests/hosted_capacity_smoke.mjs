import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fixtureStack,root,command,httpClient,delay} from './support/distributedStack.mjs';
import {seedStack} from './load/seed.mjs';
import {safeSummary,validateFixture,validateRehearsal} from './load/hosted/config.mjs';

// Only this new UUID project is touched. The k6 container shares its ingress network namespace,
// so loopback:8080 reaches the same private test service without opening another host port.
const image='grafana/k6:1.6.1@sha256:a5ad6bc089a08d77c3ec49f3db8c6fa7a148e4073efcac44c675dbaf3568d8e1';
const stack=await fixtureStack(),name=`${stack.project}-capacity`,directory=path.join(stack.directory,'capacity');
fs.mkdirSync(directory,{mode:0o700});
try{
  await stack.run(['build']);await stack.run(['up','-d']);
  const seeded=await seedStack(stack),accounts=[];
  const inventoryOutput=await stack.sql(fs.readFileSync(path.join(root,'tests','load','hosted','inventory.sql'),'utf8'));
  const inventory=JSON.parse(inventoryOutput.split(/\r?\n/).find(line=>line.startsWith('{')));
  assert.equal(inventory.accounts,8);assert.equal(inventory.applications,seeded.total);assert.equal(inventory.stages,seeded.total*3);
  fs.writeFileSync(path.join(directory,'inventory.json'),JSON.stringify(inventory,null,2));
  for(const identity of seeded.fixture.accounts.slice(0,6)){
    if(accounts.length)await delay(3500);
    const client=httpClient(stack.base);
    await client.call('/api/auth/login',{method:'POST',form:true,data:identity,headers:await client.csrf()});
    const csrf=await client.csrf(),page=await client.call('/api/v2/applications?size=8');
    accounts.push({...identity,applicationId:page.data.content[0].id,cookie:`JSESSIONID=${client.cookies.get('JSESSIONID')}`,csrfHeader:'X-CSRF-TOKEN',csrfToken:csrf['X-CSRF-TOKEN']});
  }
  const ingress=await stack.inspect('ingress'),api=await stack.inspect('api-a');
  const plan={kind:'campus-hosted-capacity-v1',runId:stack.project.slice('campus-test-'.length),base:'http://127.0.0.1:8080',disposable:true,rehearsal:true,targetsApproved:false,today:new Date().toISOString().slice(0,10),
    dataset:{accounts:inventory.accounts,applications:inventory.applications,stages:inventory.stages,largestHistory:inventory.largestHistory,observedAt:inventory.observedAt,evidenceId:'isolated-seed-inventory'},
    deployment:{imageDigest:api.Image,resourcesId:'compose-scalable-rehearsal',monthlyCostUsd:0},
    phases:{rehearsal:{durationSeconds:6,foregroundRate:10,preferencesRate:2,authRate:1,foregroundVUs:4,preferencesVUs:1,authVUs:1}}};
  const fixture={kind:'campus-hosted-sessions-v1',runId:plan.runId,base:plan.base,accounts,authIdentities:seeded.fixture.accounts.slice(6)};validateFixture(fixture,plan,'rehearsal');
  fs.writeFileSync(path.join(directory,'plan.json'),JSON.stringify(plan),{mode:0o600});
  fs.writeFileSync(path.join(directory,'sessions.private.json'),JSON.stringify(fixture),{mode:0o600});
  // Linux CI must read/write the host-owned 0700/0600 fixtures without relaxing secret permissions.
  const user=`${process.getuid?.()??10001}:${process.getgid?.()??10001}`;
  const result=await command(stack.docker,['run','--rm','--name',name,'--network',`container:${ingress.Id}`,
    '--user',user,
    '-v',`${path.join(root,'tests','load','hosted')}:/scripts:ro`,'-v',`${directory}:/fixture`,
    '-e','CAMPUS_CAPACITY_PLAN=/fixture/plan.json','-e','CAMPUS_CAPACITY_FIXTURE=/fixture/sessions.private.json',
    '-e','CAMPUS_CAPACITY_PHASE=rehearsal','-e','CAMPUS_CAPACITY_OUT=/fixture/rehearsal.json',image,'run','--quiet','/scripts/capacity.k6.js'],{env:stack.env,timeout:120000});
  fs.writeFileSync(path.join(directory,'k6.log'),stack.redact(result.output));
  const report=JSON.parse(fs.readFileSync(path.join(directory,'rehearsal.json'),'utf8'));
  validateRehearsal(report,result.code);
  assert.equal(result.code,report.workloadPassed?0:99,stack.redact(result.output.slice(-3000)));
  assert.equal(report.mode,'rehearsal');assert.equal(report.targetsApproved,false);
  assert.equal(report.metrics.unexpected_status.values.rate,0,'Functional requests must succeed even when local capacity gates fail.');
  assert.ok(report.metrics.auth_completed.values.count>=1,'Real authenticated CSRF rotation/logout flows are required.');
  assert.ok(report.metrics['route_requests{route:update}'].values.count>=1,'Real versioned writes must run.');
  // Re-evaluate from measured values, rather than treating k6 exit status alone as proof.
  assert.equal(safeSummary(plan,'rehearsal',{metrics:report.metrics}).workloadPassed,report.workloadPassed);
  console.log(`Hosted-capacity tooling functional rehearsal passed: ${report.metrics.capacity_requests.values.count} HTTP requests, successful login/logout, preferences and versioned writes. Capacity gates ${report.workloadPassed?'passed':'failed'} on this local host; recorded in the report. This is not hosted capacity evidence.`);
}catch(error){console.error(stack.redact(error.message));process.exitCode=1;}
finally{
  assert.match(name,/^campus-test-[a-f0-9-]{36}-capacity$/);
  await command(stack.docker,['rm','-f',name],{env:stack.env});
  await stack.dispose();
  for(const file of [path.join(directory,'sessions.private.json'),path.join(stack.directory,'accounts.private.json')])fs.rmSync(file,{force:true});
}
