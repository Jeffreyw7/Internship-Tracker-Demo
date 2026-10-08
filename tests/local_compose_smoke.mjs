/** Qualifies the documented single-host PostgreSQL launch with owned disposable volumes. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fixtureStack,httpClient,until} from './support/distributedStack.mjs';

const stack=await fixtureStack({composeFile:'compose.yaml'});
const override=path.join(stack.directory,'local-ports.yaml');
fs.writeFileSync(override,`services:\n  app:\n    ports: !override\n      - "127.0.0.1:${stack.env.INGRESS_PORT}:8080"\n  mail:\n    ports: !reset []\n`);
try{
  const topology=await stack.config();
  assert.ok(topology.services['export-worker'],'Default PostgreSQL Compose must supply its export processor.');
  assert.equal(topology.services.app.environment.SPRING_PROFILES_ACTIVE,'prod');
  if(process.argv.includes('--topology-only')){
    console.log('PASS: default local Compose supplies export processing and uses its supported single-host profile.');
    process.exitCode=0;
  }else{
  await stack.run(['build']);await stack.run(['-f',override,'up','-d']);
  const client=httpClient(stack.base);
  await until(async()=>{await client.call('/readyz');return true;},'local Compose readiness');
  // Synthetic administrative fixture bypasses mail: this scenario qualifies export processing.
  const owner='10000000-0000-4000-8000-000000000001';
  await stack.sql(`INSERT INTO campus_users(id,email,display_name,password_hash,created_at,email_verified_at)
    VALUES('${owner}','local-export@example.test','Local export fixture','synthetic',now(),now());`);
  const created=(await stack.sql(`INSERT INTO export_jobs(id,owner_id,state,created_at,expires_at,next_attempt_at)
    VALUES(gen_random_uuid(),'${owner}','PENDING',now(),now()+interval '24 hours',now()) RETURNING id;`)).trim().split('\n')[0];
  await until(async()=>{
    const state=(await stack.sql(`SELECT state FROM export_jobs WHERE id='${created}';`)).trim();
    assert.notEqual(state,'FAILED');return state==='READY';
  },'default local Compose CSV processing');
  assert.equal((await stack.inspect('export-worker')).Config.Env.find(value=>value.startsWith('DATABASE_USERNAME=')),'DATABASE_USERNAME=campus_export_worker');
  assert.equal((await stack.inspect('app')).Mounts.find(mount=>mount.Destination==='/app/data/exports').RW,false);
  assert.equal((await stack.inspect('export-worker')).Mounts.find(mount=>mount.Destination==='/app/data/exports').RW,true);
  // The API must be able to read the same private object with its read-only mount.
  const key=(await stack.sql(`SELECT object_key FROM export_jobs WHERE id='${created}';`)).trim();
  const csv=await stack.run(['exec','-T','app','cat',`/app/data/exports/${key}`]);assert.ok(csv.startsWith('\uFEFF"Company"'));
  console.log('PASS: default PostgreSQL Compose processes exports with a restricted worker and shared private read-only API storage.');
  }
}catch(error){console.error(stack.redact(error.stack));await stack.compose(['logs','--no-color','--tail','80']);process.exitCode=1;}
finally{await stack.dispose();}
