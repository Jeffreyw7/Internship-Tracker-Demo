import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import {httpClient,delay} from '../../support/distributedStack.mjs';
import {validateTarget,validateFixture} from './config.mjs';

export async function prepareFixture(plan,identities,{delayMs=3500}={}){
  validateTarget(plan);
  if(identities?.kind!=='campus-hosted-identities-v1'||identities.runId!==plan.runId||identities.base!==plan.base||!Array.isArray(identities.accounts))throw new Error('Identities must match the synthetic run and origin.');
  const skeleton={kind:'campus-hosted-sessions-v1',runId:plan.runId,base:plan.base,
    authIdentities:identities.authIdentities?.map(a=>({email:a.email,password:a.password})),
    accounts:identities.accounts.map(a=>({email:a.email,password:a.password,applicationId:a.applicationId,cookie:'JSESSIONID=validation-only',csrfHeader:'X-CSRF-TOKEN',csrfToken:'validation-only'}))};
  // Validate every identity and the largest phase before making even a login request.
  for(const phase of Object.keys(plan.phases))validateFixture(skeleton,plan,phase);
  if(!Number.isFinite(delayMs)||delayMs<0)throw new Error('Invalid preparation pacing.');
  const accounts=[];
  for(const a of skeleton.accounts){
    if(accounts.length)await delay(delayMs);
    const client=httpClient(plan.base);
    await client.call('/api/auth/login',{method:'POST',form:true,data:{email:a.email,password:a.password},headers:await client.csrf()});
    const headers=await client.csrf();
    const {data:detail}=await client.call(`/api/applications/${a.applicationId}`);
    if(detail.id!==a.applicationId)throw new Error('Synthetic application ownership check failed.');
    accounts.push({...a,cookie:`JSESSIONID=${client.cookies.get('JSESSIONID')}`,csrfToken:headers['X-CSRF-TOKEN']});
  }
  const fixture={...skeleton,accounts};
  for(const phase of Object.keys(plan.phases))validateFixture(fixture,plan,phase);
  return fixture;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{
    const [planFile,identitiesFile,output]=process.argv.slice(2);
    if(!planFile||!identitiesFile||!output?.endsWith('.private.json')||fs.existsSync(output))throw new Error('Use prepare.mjs plan.json identities.private.json NEW-sessions.private.json');
    const read=file=>{if(fs.statSync(file).size>32000000)throw new Error('Fixture input exceeds its bound.');return JSON.parse(fs.readFileSync(file,'utf8'));};
    const fixture=await prepareFixture(read(planFile),read(identitiesFile));
    fs.writeFileSync(output,JSON.stringify(fixture),{mode:0o600,flag:'wx'});
    console.log(`Prepared ${fixture.accounts.length} isolated synthetic session slots. Private output written.`);
  }catch{
    // HTTP assertions can contain server payloads; never echo those or an identity file.
    console.error('Session preparation failed. Check the target/fixture, ownership, pacing and admission limits; no output was written.');process.exitCode=1;
  }
}
