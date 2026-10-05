import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {fixtureStack,httpClient,until} from '../support/distributedStack.mjs';

export function historyCounts(accounts,small,large){
  assert.ok(Number.isInteger(accounts)&&accounts>=2&&accounts<=128);
  assert.ok(Number.isInteger(small)&&small>=1&&small<=1000&&Number.isInteger(large)&&large>=small&&large<=100_000);
  return Array.from({length:accounts},(_,index)=>index===accounts-1||index%10===9?large:small);
}
export function fixtureSql({accounts,passwordHash,today}){
  assert.match(passwordHash,/^\$2[aby]\$[0-9]{2}\$[A-Za-z0-9./]+$/,'Invalid fixture hash');
  assert.match(today,/^\d{4}-\d{2}-\d{2}$/,'Invalid fixture date');
  const statements=['BEGIN;'];
  for(const account of accounts){
    assert.match(account.id,/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i,'Invalid fixture UUID');
    assert.match(account.email,/^load-[0-9]+@example\.test$/,'Invalid fixture email');
    assert.ok(Number.isInteger(account.count)&&account.count>=1&&account.count<=100_000,'Invalid fixture size');
    statements.push(`INSERT INTO campus_users(id,email,display_name,password_hash,created_at,email_verified_at) VALUES('${account.id}','${account.email}','Synthetic load fixture','${passwordHash}',now(),now());`);
    statements.push(`INSERT INTO applications(id,owner_id,company,role,location,status,applied_date,deadline,follow_up_date,notes,sample,deleted,version,created_at,updated_at)
      SELECT gen_random_uuid(),'${account.id}','Synthetic company '||g,'Intern','Remote',
      (ARRAY['SAVED','APPLIED','INTERVIEWING','OFFER','REJECTED','WITHDRAWN'])[1+(g%6)],
      '${today}'::date-(g%300),CASE WHEN g%7=0 THEN NULL ELSE '${today}'::date+(g%30)-15 END,
      CASE WHEN g%5=0 THEN NULL ELSE '${today}'::date+(g%10)-5 END,
      '=Synthetic formula, "quoted" notes '||repeat('private fixture ',30),g%23=0,g%37=0,0,
      now()-interval '400 days'+g*interval '1 second',now()-g*interval '1 second'
      FROM generate_series(1,${account.count}) g;`);
    statements.push(`INSERT INTO interview_stages(id,application_id,position,name,stage_date,state)
      SELECT gen_random_uuid(),a.id,p,'Synthetic stage '||p,'${today}'::date+(p-2),
      (ARRAY['PLANNED','SCHEDULED','COMPLETED'])[p] FROM applications a CROSS JOIN generate_series(1,3) p WHERE a.owner_id='${account.id}';`);
  }
  statements.push('COMMIT; ANALYZE applications; ANALYZE interview_stages;');return statements.join('\n');
}
export async function seedStack(stack,{accounts=8,small=50,large=5003}={}){
  assert.match(stack.project,/^campus-test-[a-f0-9-]{36}$/,'Seeder requires its own disposable project.');
  const counts=historyCounts(accounts,small,large),client=httpClient(stack.base),password='Synthetic-load-fixture-123!';
  await until(async()=>{await client.call('/readyz');return true;},'seed stack readiness');
  // Obtain a real BCrypt hash through registration; only generated accounts are bulk-seeded.
  await client.call('/api/auth/register',{method:'POST',data:{email:'seed-template@example.test',password,displayName:'Synthetic seed template',includeSamples:false},headers:await client.csrf(),expected:202});
  const passwordHash=(await stack.sql("SELECT password_hash FROM campus_users WHERE email='seed-template@example.test';")).trim();
  const generated=counts.map((count,index)=>({id:randomUUID(),email:`load-${index+1}@example.test`,count}));
  await stack.sql(fixtureSql({accounts:generated,passwordHash,today:new Date().toISOString().slice(0,10)}));
  const fixture={kind:'campus-disposable-load-v1',base:stack.base,accounts:generated.map(({email})=>({email,password}))};
  fs.writeFileSync(path.join(stack.directory,'accounts.private.json'),JSON.stringify(fixture),{mode:0o600});
  return {fixture,counts,total:counts.reduce((sum,value)=>sum+value,0)};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const stack=await fixtureStack();
  try{
    await stack.run(['build']);await stack.run(['up','-d']);await seedStack(stack);
    console.log(`Disposable fixture ready at ${stack.base}. Private account file: ${path.join(stack.directory,'accounts.private.json')}. Run the load runner separately; Ctrl+C removes only this fixture.`);
    await new Promise(resolve=>{process.once('SIGINT',resolve);process.once('SIGTERM',resolve);});
  }catch(error){console.error(stack.redact(error.message));process.exitCode=1;}
  finally{await stack.dispose();fs.rmSync(path.join(stack.directory,'accounts.private.json'),{force:true});}
}
