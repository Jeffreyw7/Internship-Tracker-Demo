import http from 'k6/http';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Counter,Rate,Trend} from 'k6/metrics';
import {buildOptions,validateFixture,safeSummary,statusBuckets} from './config.mjs';

const plan=JSON.parse(open(__ENV.CAMPUS_CAPACITY_PLAN));
const phase=__ENV.CAMPUS_CAPACITY_PHASE;
export const options=buildOptions(plan,phase);
const accounts=new SharedArray('private capacity slots',()=>{
  const fixture=JSON.parse(open(__ENV.CAMPUS_CAPACITY_FIXTURE));
  return validateFixture(fixture,plan,phase).accounts;
});
const authIdentities=new SharedArray('private rotating auth identities',()=>{
  const fixture=JSON.parse(open(__ENV.CAMPUS_CAPACITY_FIXTURE));
  return validateFixture(fixture,plan,phase).authIdentities;
});
const requests=new Counter('capacity_requests'),bootstrap=new Counter('bootstrap_requests');
const foregroundIterations=new Counter('foreground_iterations'),preferencesIterations=new Counter('preferences_iterations');
const authIterations=new Counter('auth_iterations'),authCompleted=new Counter('auth_completed');
const unexpected=new Rate('unexpected_status'),apiLatency=new Trend('api_latency',true),passwordLatency=new Trend('password_latency',true);
const statuses=new Counter('response_status'),routeRequests=new Counter('route_requests');
let detail,bootstrapped=false;
function slot(){
  const account=accounts[exec.vu.idInTest-1];
  if(!account)throw new Error('No isolated fixture slot for this VU.');
  return account;
}
function request(route,path,{method='GET',body=null,headers={},expected=200,text=false,isBootstrap=false}={}){
  const start=Date.now();
  const response=http.request(method,plan.base+path,body,{headers,timeout:'12s',responseType:text?'text':'none',redirects:0,tags:{name:route}});
  const elapsed=Date.now()-start;
  (isBootstrap?bootstrap:requests).add(1);
  // Only fixed route names and numeric statuses; no IDs, URLs, cookies or credentials in tags.
  const status=String(response.status);
  statuses.add(1,{route,status:statusBuckets.includes(status)?status:'other'});
  unexpected.add(response.status!==expected);
  if(['list','summary','activities','detail','update','preferences'].includes(route)&&!isBootstrap){apiLatency.add(elapsed,{route});routeRequests.add(1,{route});}
  if(route==='login')passwordLatency.add(elapsed);
  return response;
}
function json(response){try{return response.json();}catch{unexpected.add(true);return null;}}
function fixtureHeaders(account){return {Cookie:account.cookie};}
export function foreground(){
  const a=slot(),choice=exec.vu.iterationInScenario%10,headers=fixtureHeaders(a);
  if(!bootstrapped){
    bootstrapped=true;
    const response=request('detail',`/api/applications/${a.applicationId}`,{headers,text:true,isBootstrap:true});
    detail=response.status===200?json(response):null;
  }
  if(choice<5)request('list','/api/v2/applications?size=8',{headers});
  else if(choice<7)request('summary',`/api/v2/applications/summary?today=${plan.today}`,{headers});
  else if(choice===7)request('activities',`/api/v2/activities?size=25&today=${plan.today}`,{headers});
  else if(choice===8){
    const response=request('detail',`/api/applications/${a.applicationId}`,{headers,text:true});
    if(response.status===200)detail=json(response);
  }else if(detail){
    // Each VU exclusively owns its mutation slot. A conflict is a failed qualification, never replayed.
    const payload={company:detail.company,role:detail.role,location:detail.location,status:detail.status,
      appliedDate:detail.appliedDate||null,deadline:detail.deadline||null,followUpDate:detail.followUpDate||null,
      notes:'Synthetic capacity mutation',stages:detail.stages.map(s=>({id:s.id,name:s.name,date:s.date||null,state:s.state}))};
    const response=request('update',`/api/applications/${a.applicationId}`,{method:'PUT',body:JSON.stringify(payload),text:true,
      headers:{...headers,[a.csrfHeader]:a.csrfToken,'If-Match':String(detail.version),'Content-Type':'application/json'}});
    if(response.status===200)detail=json(response);
  }else unexpected.add(true);
  foregroundIterations.add(1);
}
export function preferences(){
  request('preferences','/api/me/preferences',{headers:fixtureHeaders(slot())});preferencesIterations.add(1);
}
export function auth(){
  const a=authIdentities[exec.scenario.iterationInTest%authIdentities.length];http.cookieJar().clear(plan.base);
  try{
    const beforeResponse=request('csrf','/api/auth/csrf',{text:true});
    const before=beforeResponse.status===200?json(beforeResponse):null;
    if(!before?.token||before.headerName!=='X-CSRF-TOKEN'){unexpected.add(true);return;}
    const login=request('login','/api/auth/login',{method:'POST',text:true,
      body:`email=${encodeURIComponent(a.email)}&password=${encodeURIComponent(a.password)}`,
      headers:{[before.headerName]:before.token,'Content-Type':'application/x-www-form-urlencoded'}});
    const identity=login.status===200?json(login):null;
    const afterResponse=request('csrf','/api/auth/csrf',{text:true});
    const after=afterResponse.status===200?json(afterResponse):null;
    if(!after?.token||after.headerName!=='X-CSRF-TOKEN'){unexpected.add(true);return;}
    const logout=request('logout','/api/auth/logout',{method:'POST',expected:204,headers:{[after.headerName]:after.token}});
    if(identity?.email===a.email&&before.token!==after.token&&logout.status===204)authCompleted.add(1);
    else unexpected.add(true);
  }finally{authIterations.add(1);}
}
export function handleSummary(data){
  const result=safeSummary(plan,phase,data);
  // Supplement only explicitly bounded counters. Never export the raw k6 summary or fixtures.
  result.bootstrapRequests=data.metrics.bootstrap_requests?.values.count??0;
  return {[__ENV.CAMPUS_CAPACITY_OUT||'capacity-result.json']:JSON.stringify(result,null,2),
    stdout:`Capacity ${phase}: ${result.workloadPassed?'workload gates passed':'workload gates failed'} (${result.mode}); ${result.workload.achievedHttpRate.toFixed(1)} measured HTTP/s.\n`};
}
