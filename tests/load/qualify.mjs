/** Short, repeatable local ramp/burst/soak and rollout experiment; all data is disposable. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fixtureStack,httpClient,until,command} from '../support/distributedStack.mjs';
import {seedStack} from './seed.mjs';
import {prepareSessions,runHttpLoad} from './run.mjs';
import {reportMarkdown} from './report.mjs';

const stack=await fixtureStack();
const document={kind:'campus-local-qualification-v1',reports:[],resources:[]};
async function resources(label){
  const metrics={label,api:{}};
  for(const service of ['api-a','api-b']){
    const raw=await stack.run(['exec','-T',service,'sh','-ec','curl -fsS --max-time 5 -H "Authorization: Bearer $METRICS_TOKEN" http://127.0.0.1:8080/actuator/prometheus']);
    metrics.api[service]=raw.split('\n').filter(line=>/^(jvm_memory_used_bytes|jvm_gc_pause_seconds_(sum|count|max)|hikaricp_connections_(active|pending|max)|hikaricp_connections_acquire_seconds_(sum|count|max))[{ ]/.test(line));
  }
  metrics.redis=(await stack.run(['exec','-T','redis','sh','-ec','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli INFO memory'])).split('\n').filter(line=>/^(used_memory:|maxmemory:|mem_fragmentation_ratio:)/.test(line)).map(line=>line.trim());
  metrics.queues=(await stack.sql("SELECT 'mail',status,count(*),coalesce(max(extract(epoch from now()-created_at)),0) FROM email_outbox GROUP BY status UNION ALL SELECT 'export',state,count(*),coalesce(max(extract(epoch from now()-created_at)),0) FROM export_jobs GROUP BY state;")).trim().split('\n');
  document.resources.push(metrics);
}
async function phase(name,rate,seconds,scenario='foreground',sessions){
  console.log(`Measuring ${name} at ${rate} offered requests/second.`);
  await resources(`${name}-before`);
  const report=await runHttpLoad({base:stack.base,fixture:document.fixture,rate,durationMs:seconds*1000,maxConcurrency:32,scenario,sessions});
  report.scenario=name;document.reports.push(report);await resources(`${name}-after`);
  console.log(`${name}: ${report.started}/${report.scheduled} arrivals started; ${report.dropped} dropped.`);
}
try{
  await stack.run(['build']);await stack.run(['up','-d']);
  document.images=await stack.manifest();
  const topology=await stack.config();
  document.limits=Object.fromEntries(Object.entries(topology.services).filter(([,service])=>service.mem_limit)
    .map(([name,service])=>[name,{memoryBytes:service.mem_limit,cpus:service.cpus,pids:service.pids_limit}]));
  const seed=await seedStack(stack);document.dataset={accounts:seed.counts.length,applications:seed.total,historyCounts:seed.counts,stagesPerApplication:3};document.fixture=seed.fixture;
  const sessions=await prepareSessions(stack.base,seed.fixture);
  await phase('reference-20',20,20,'foreground',sessions);
  await phase('ramp-50',50,20,'foreground',sessions);
  // Put real mail production/delivery and CSV uploads alongside foreground work.
  const capture=httpClient(stack.base);
  await capture.call('/api/auth/register',{method:'POST',data:{email:'contention@example.test',password:'Synthetic-mail-load-123!',displayName:'Synthetic contention',includeSamples:false},headers:await capture.csrf(),expected:202});
  await phase('mail-csv-contention-50',50,30,'contention',sessions);
  assert.ok(Number((await stack.sql("SELECT count(*) FROM email_outbox WHERE status='SENT';")).trim())>=2);
  assert.equal(Number((await stack.sql("SELECT count(*) FROM export_jobs WHERE state='READY';")).trim()),4);
  await phase('burst-200',200,15,'foreground',sessions);
  // Add one API replacement without stopping either existing peer, within the documented 24-connection ceiling.
  const config=await stack.config(),third={...config.services['api-b'],image:(await stack.inspect('api-b')).Config.Image};delete third.build;delete third.depends_on;
  const override=path.join(stack.directory,'rollout.private.json');fs.writeFileSync(override,JSON.stringify({services:{'api-c':third}}),{mode:0o600});
  await stack.run(['-f',override,'up','-d','--no-deps','api-c']);
  await until(async()=>{const ids=(await stack.run(['-f',override,'ps','-q','api-c'])).trim();const result=await command(stack.docker,['inspect',ids],{env:stack.env});return JSON.parse(result.output)[0].State.Health.Status==='healthy';},'overlapping replacement readiness');
  document.rolloutConnections=(await stack.sql("SELECT usename,count(*) FROM pg_stat_activity WHERE usename LIKE 'campus_%' GROUP BY usename;")).trim().split('\n');
  await phase('overlap-soak-50',50,60,'foreground',sessions);
  await phase('invalid-auth-5',5,10,'auth',sessions);
  delete document.fixture;
  fs.writeFileSync(path.join(stack.directory,'measurements.json'),JSON.stringify(document,null,2));
  fs.writeFileSync(path.join(stack.directory,'measurements.md'),reportMarkdown(document.reports));
  console.log(`Local reports saved to ${stack.directory}.`);
}catch(error){console.error(stack.redact(error.stack));await stack.compose(['logs','--no-color','--tail','100']);process.exitCode=1;}
finally{await stack.dispose();for(const name of ['accounts.private.json','rollout.private.json'])fs.rmSync(path.join(stack.directory,name),{force:true});}
