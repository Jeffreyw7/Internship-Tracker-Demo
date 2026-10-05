/** Owns an isolated Compose project. Never points at a user's stack or existing volume. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {assertTopology,fixtureStack,httpClient,until,delay} from './support/distributedStack.mjs';

const legacy=process.argv.includes('--legacy-topology');
const stack=await fixtureStack({composeFile:legacy?'compose.yaml':'compose.scalable.yaml'});
let started=false;
try{
  assertTopology(await stack.config());
  if(process.argv.includes('--topology-only')){console.log('PASS: private replicated topology and migration gates.');}
  else{
    console.log('Building the isolated Java 17 / static UI stack.');
    await stack.run(['build']);started=true;
    await stack.run(['up','-d']);
    await stack.manifest();
    const client=httpClient(stack.base);
    await until(async()=>{await client.call('/readyz');return true;},'gateway readiness');
    for(const service of ['api-a','api-b','database','redis','storage','mail','mail-worker','export-worker']){
      const container=await stack.inspect(service);assert.equal(Object.values(container.NetworkSettings.Ports??{}).filter(Boolean).length,0,`${service} publishes a private port.`);
    }
    const {data:html,headers:htmlHeaders}=await client.call('/');assert.ok(html.includes('id="root"'));assert.match(htmlHeaders.get('cache-control'),/no-cache/);
    for(const match of html.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)){
      const asset=await client.call(match[1]);assert.ok(asset.data.length>100);
      assert.match(asset.headers.get('cache-control'),match[1].startsWith('/assets/')?/immutable/:/no-cache/);
    }
    const bootstrap=await client.call('/theme-init.js');assert.match(bootstrap.headers.get('cache-control'),/no-cache/);
    for(const route of ['/actuator/prometheus','/actuator/env','/actuator/health'])await client.call(route,{expected:404});
    const peers=new Set();for(let i=0;i<8;i++){const response=await client.call('/api/health');assert.match(response.headers.get('cache-control'),/no-store/);peers.add(response.headers.get('x-campus-instance'));}
    assert.equal(peers.size,2,'Round-robin must reach two API peers.');
    const identity={email:'distributed@example.test',password:'Synthetic-qualification-123!'};
    const beforeLogin=await client.csrf();
    await client.call('/api/auth/register',{method:'POST',data:{...identity,displayName:'Distributed fixture',includeSamples:true},headers:beforeLogin,expected:202});
    const token=await until(async()=>{const inbox=await stack.mail();if(!inbox.messages?.length)return false;
      const raw=await stack.mailBody(inbox.messages[0].ID);return /#verify-email=([A-Za-z0-9_-]{43})/.exec(raw)?.[1];},'dedicated mail delivery');
    await client.call('/api/auth/email-verification/confirm',{method:'POST',data:{token,password:identity.password},headers:await client.csrf()});
    await client.call('/api/auth/login',{method:'POST',data:identity,form:true,headers:await client.csrf()});
    const authenticated=await client.csrf();
    const draft={company:'Replicated test',role:'Intern',location:'Remote',status:'Applied',appliedDate:'2026-10-05',deadline:null,followUpDate:null,notes:'Private CSV across peers',stages:[]};
    await client.call('/api/applications',{method:'POST',data:draft,headers:beforeLogin,expected:403});
    const {data:application}=await client.call('/api/applications',{method:'POST',data:draft,headers:{...authenticated,'X-Forwarded-For':'198.51.100.2, 203.0.113.8',Forwarded:'for=198.51.100.2'},expected:201});
    // Mutation headers and query parameters survive routing; old versions are not replayed.
    const route=`/api/applications/${application.id}`;
    const updated=await client.call(route,{method:'PUT',data:{...draft,notes:'Updated once'},headers:{...authenticated,'If-Match':String(application.version)}});
    await client.call(route,{method:'PUT',data:draft,headers:{...authenticated,'If-Match':String(application.version)},expected:409});
    const page=await client.call('/api/v2/applications?q=Replicated&size=8');assert.equal(page.data.content.length,1);
    const {data:job}=await client.call('/api/v2/exports',{method:'POST',data:{},headers:authenticated,expected:202});
    await until(async()=>{const status=await client.call(`/api/v2/exports/${job.id}`);assert.notEqual(status.data.state,'FAILED');return status.data.state==='READY';},'private export worker');
    const csv=await client.call(`/api/v2/exports/${job.id}/download`);assert.match(csv.data,/Updated once/);assert.match(csv.headers.get('cache-control'),/no-store/);
    // Restart one peer. Reads may be retried by this test; application writes are never replayed.
    await stack.run(['restart','api-a']);await until(async()=>{await client.call('/api/auth/me');return true;},'shared session after peer restart');
    await stack.run(['stop','-t','35','api-a']);
    await until(async()=>{await client.call('/api/auth/me');return true;},'remaining peer');
    await stack.run(['start','--wait','--wait-timeout','120','api-a']);await until(async()=>{await client.call('/readyz');return true;},'peer recovery');
    await stack.run(['restart','mail-worker','export-worker']);
    await client.call('/api/auth/logout',{method:'POST',headers:await client.csrf(),expected:204});
    for(let i=0;i<4;i++)await client.call('/api/auth/me',{expected:401});
    // Missing Redis is an outage (503), never anonymous success/local sessions.
    await stack.run(['stop','redis']);await client.call('/api/auth/csrf',{expected:503});await client.call('/readyz',{expected:503});await client.call('/livez');
    await stack.run(['start','redis']);await until(async()=>{await client.call('/readyz');return true;},'Redis recovery');
    // Noeviction under a memory ceiling preserves records and makes writes fail closed.
    await stack.run(['exec','-T','redis','sh','-ec','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli CONFIG SET maxmemory 1']);
    await client.call('/api/auth/csrf',{expected:503});
    await stack.run(['exec','-T','redis','sh','-ec','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli CONFIG SET maxmemory 67108864']);
    await until(async()=>{await client.call('/api/auth/csrf');return true;},'Redis memory recovery');
    // Keep only this fixture's database alive, install an invalid new migration, then retry startup.
    await stack.run(['stop','-t','35','ingress','api-a','api-b','mail-worker','export-worker']);
    await stack.run(['rm','-f','migrate','api-a','api-b','mail-worker','export-worker','ingress']);
    const invalid=path.join(stack.directory,'V999__qualification_failure.sql');fs.writeFileSync(invalid,'SELECT definitely_missing_qualification_function();');
    const override=path.join(stack.directory,'bad-migration.json');fs.writeFileSync(override,JSON.stringify({services:{migrate:{volumes:[`${invalid.replaceAll('\\','/')}:/app/classes/db/migration/V999__qualification_failure.sql:ro`]}}}));
    const failed=await stack.compose(['-f',override,'up','-d']);assert.notEqual(failed.code,0,'Bad migration must reject dependent startup.');
    for(const service of ['api-a','api-b','mail-worker','export-worker'])assert.equal((await stack.inspect(service)).State.Running,false,`${service} started after migration failure.`);
    console.log('PASS: two-peer sessions/CSRF/CRUD/logout, private ports/storage/workers, cache policy, restart, Redis outage/full and migration failure gating.');
  }
}catch(error){console.error(stack.redact(error.stack));process.exitCode=1;if(started)await stack.compose(['logs','--no-color','--tail','120']);}
finally{if(started)await stack.dispose();else fs.rmSync(stack.env.S3_CREDENTIALS_FILE,{force:true});}
