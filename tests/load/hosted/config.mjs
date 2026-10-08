// Pure JS shared by Node contract tests and the pinned k6 runtime; no remote imports.
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const label=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
export const apiRoutes=['list','summary','activities','detail','update','preferences'];
export const statusRoutes=[...apiRoutes,'csrf','login','logout'];
export const statusBuckets=['0','200','204','400','401','403','404','409','429','500','502','503','504','other'];
const requireValue=(condition,message)=>{if(!condition)throw new Error(message);};
const integer=(value,min,max)=>Number.isInteger(value)&&value>=min&&value<=max;
export function validateTarget(plan){
  requireValue(plan?.kind==='campus-hosted-capacity-v1'&&uuid.test(plan.runId),'Invalid capacity run manifest.');
  requireValue(plan.disposable===true,'Only an explicitly disposable qualification target is allowed.');
  const secure=/^https:\/\/[a-z0-9]+(?:[.-][a-z0-9]+)*(?::443)?$/i.test(plan.base);
  const loopback=/^http:\/\/(?:127\.0\.0\.1|localhost):[1-9][0-9]{0,4}$/.test(plan.base);
  requireValue(secure||(plan.rehearsal===true&&loopback),'Supply an exact HTTPS origin, or loopback HTTP for a rehearsal.');
  requireValue(typeof plan.rehearsal==='boolean'&&typeof plan.targetsApproved==='boolean','Declare rehearsal and target approval.');
  requireValue(/^\d{4}-\d{2}-\d{2}$/.test(plan.today)&&Number.isFinite(Date.parse(`${plan.today}T12:00:00Z`)),'Invalid fixture calendar date.');
  requireValue(plan.phases&&Object.keys(plan.phases).length>0&&Object.keys(plan.phases).length<=5,'Declare bounded phases.');
  for(const [name,p] of Object.entries(plan.phases)){
    requireValue(['reference','sustained','burst','soak','rehearsal'].includes(name),'Unknown phase.');
    requireValue(integer(p.durationSeconds,2,21600),'Phase duration must be 2s..6h.');
    for(const key of ['foregroundRate','preferencesRate','authRate'])requireValue(integer(p[key],1,10000),'Invalid offered rate.');
    requireValue(p.foregroundRate+p.preferencesRate+p.authRate*4<=10000,'Total planned HTTP rate exceeds 10000/s.');
    for(const key of ['foregroundVUs','preferencesVUs','authVUs'])requireValue(integer(p[key],1,5000),'Invalid client slot bound.');
    requireValue(p.foregroundVUs+p.preferencesVUs+p.authVUs<=5000,'At most 5000 preallocated client slots.');
  }
  const d=plan.dataset;
  for(const key of ['accounts','applications','stages','largestHistory'])requireValue(integer(d?.[key],1,1000000000),'Declare measured dataset counts.');
  requireValue(label.test(d.evidenceId)&&Number.isFinite(Date.parse(d.observedAt)),'Dataset needs a dated inventory evidence ID.');
  requireValue(/^sha256:[a-f0-9]{64}$/.test(plan.deployment?.imageDigest),'Record an immutable image digest.');
  requireValue(label.test(plan.deployment.resourcesId),'Record a protected resource/cost inventory evidence ID.');
  requireValue(Number.isFinite(plan.deployment.monthlyCostUsd)&&plan.deployment.monthlyCostUsd>=0,'Record estimated monthly resource cost.');
  return plan;
}
export function validateFixture(fixture,plan,phase){
  validateTarget(plan);const p=plan.phases[phase];requireValue(p,'Unknown phase.');
  requireValue(fixture?.kind==='campus-hosted-sessions-v1'&&fixture.runId===plan.runId&&fixture.base===plan.base,'Private fixture must match the run and origin.');
  requireValue(Array.isArray(fixture.accounts)&&fixture.accounts.length<=5000&&fixture.accounts.length>=p.foregroundVUs+p.preferencesVUs+p.authVUs,'Insufficient isolated client slots (or more than 5000).');
  const ids=new Set(),emails=new Set();
  for(const a of fixture.accounts){
    requireValue(/^load-[0-9]+@example\.test$/.test(a.email)&&!emails.has(a.email),'Use distinct synthetic test identities.');emails.add(a.email);
    requireValue(typeof a.password==='string'&&a.password.length>=12&&a.password.length<=128,'Invalid synthetic password.');
    requireValue(/^JSESSIONID=[A-Za-z0-9._-]{1,256}$/.test(a.cookie),'Invalid private session cookie.');
    requireValue(a.csrfHeader==='X-CSRF-TOKEN'&&typeof a.csrfToken==='string'&&a.csrfToken.length>=1&&a.csrfToken.length<=512&&!/[\r\n]/.test(a.csrfToken),'Invalid CSRF fixture.');
    requireValue(uuid.test(a.applicationId)&&!ids.has(a.applicationId),'Each client needs a distinct mutation application.');ids.add(a.applicationId);
  }
  const minimum=plan.rehearsal?1:p.authRate*60;
  requireValue(Array.isArray(fixture.authIdentities)&&fixture.authIdentities.length>=minimum&&fixture.authIdentities.length<=100000,`Independent auth identity pool needs ${minimum}..100000 identities.`);
  for(const a of fixture.authIdentities){
    requireValue(/^load-[0-9]+@example\.test$/.test(a.email)&&!emails.has(a.email),'Auth identities must be distinct synthetic accounts, separate from foreground sessions.');emails.add(a.email);
    requireValue(typeof a.password==='string'&&a.password.length>=12&&a.password.length<=128,'Invalid synthetic auth password.');
  }
  return fixture;
}
export function buildOptions(plan,phase){
  validateTarget(plan);const p=plan.phases[phase];requireValue(p,'Unknown phase.');
  const scenario=(exec,rate,vus)=>({executor:'constant-arrival-rate',exec,rate,timeUnit:'1s',duration:`${p.durationSeconds}s`,preAllocatedVUs:vus,maxVUs:vus,gracefulStop:'60s'});
  const thresholds={
    capacity_requests:[`count>=${(p.foregroundRate+p.preferencesRate+p.authRate*4)*p.durationSeconds}`],
    foreground_iterations:[`count>=${p.foregroundRate*p.durationSeconds}`],preferences_iterations:[`count>=${p.preferencesRate*p.durationSeconds}`],
    auth_iterations:[`count>=${p.authRate*p.durationSeconds}`],auth_completed:[`count>=${p.authRate*p.durationSeconds}`],
    dropped_iterations:['count==0'],unexpected_status:['rate==0'],password_latency:['p(95)<2000'],
  };
  for(const route of apiRoutes)thresholds[`api_latency{route:${route}}`]=['p(95)<300','p(99)<1000'];
  for(const route of apiRoutes)thresholds[`route_requests{route:${route}}`]=['count>=1'];
  for(const route of statusRoutes)for(const status of statusBuckets)thresholds[`response_status{route:${route},status:${status}}`]=['count>=0'];
  return {scenarios:{foreground:scenario('foreground',p.foregroundRate,p.foregroundVUs),preferences:scenario('preferences',p.preferencesRate,p.preferencesVUs),auth:scenario('auth',p.authRate,p.authVUs)},
    thresholds,discardResponseBodies:true,noCookiesReset:true,maxRedirects:0,insecureSkipTLSVerify:false,
    systemTags:['name','method','status','scenario','expected_response'],summaryTrendStats:['count','p(50)','p(95)','p(99)','max'],setupTimeout:'60s'};
}
const allowedValues=['count','rate','value','min','max','p(50)','p(95)','p(99)','passes','fails'];
function measurement(metric){return {values:Object.fromEntries(allowedValues.filter(k=>Number.isFinite(metric?.values?.[k])).map(k=>[k,metric.values[k]]))};}
export function safeSummary(plan,phase,data){
  const options=buildOptions(plan,phase),metrics={},checks=[];
  for(const [name,expressions] of Object.entries(options.thresholds)){
    metrics[name]=measurement(data.metrics?.[name]);
    for(const expression of expressions){
      const [,stat,operator,limit]=expression.match(/^(count|rate|p\(95\)|p\(99\))(>=|==|<)([0-9.]+)$/);
      const value=metrics[name].values[stat];
      // k6 omits the dropped counter when no iteration was dropped. All workload metrics must exist.
      const optional=name==='dropped_iterations'||name.startsWith('response_status{');
      const actual=optional&&value===undefined?0:value;
      const passed=Number.isFinite(actual)&&(operator==='>='?actual>=Number(limit):operator==='=='?actual===Number(limit):actual<Number(limit));
      checks.push({metric:name,expression,passed});
    }
  }
  // Empty Trend submetrics may contain zero percentiles: require actual successful request coverage.
  checks.push({metric:'password_latency',expression:'sample count covers password attempts',passed:(metrics.password_latency.values.count??0)>=plan.phases[phase].authRate*plan.phases[phase].durationSeconds});
  for(const route of apiRoutes)checks.push({metric:`api_latency{route:${route}}`,expression:'latency samples exist',passed:(metrics[`api_latency{route:${route}}`].values.count??0)>0});
  for(const name of ['vus','vus_max','data_received','data_sent'])if(data.metrics?.[name])metrics[name]=measurement(data.metrics[name]);
  const p=plan.phases[phase],d=plan.dataset,release=plan.deployment;
  return {kind:'campus-capacity-result-v1',runId:plan.runId,phase,mode:plan.rehearsal?'rehearsal':'hosted',targetsApproved:plan.targetsApproved,
    dataset:{accounts:d.accounts,applications:d.applications,stages:d.stages,largestHistory:d.largestHistory,observedAt:d.observedAt,evidenceId:d.evidenceId},
    deployment:{imageDigest:release.imageDigest,resourcesId:release.resourcesId,monthlyCostUsd:release.monthlyCostUsd},
    workload:{...Object.fromEntries(['durationSeconds','foregroundRate','preferencesRate','authRate','foregroundVUs','preferencesVUs','authVUs'].map(k=>[k,p[k]])),
      plannedHttpRate:p.foregroundRate+p.preferencesRate+p.authRate*4,achievedHttpRate:metrics.capacity_requests.values.rate??(metrics.capacity_requests.values.count??0)/p.durationSeconds},
    metrics,checks,workloadPassed:checks.every(c=>c.passed)};
}
export function acceptCapacity(reports){
  const reasons=[],byPhase=new Map();
  const reject=(condition,reason)=>{if(!condition)reasons.push(reason);};
  reject(Array.isArray(reports)&&reports.length===3,'Supply exactly sustained, burst and soak reports.');
  const first=reports?.[0];
  for(const report of reports??[]){
    reject(report.kind==='campus-capacity-result-v1'&&report.mode==='hosted'&&report.targetsApproved===true,'Hosted target approval is missing, or this is a rehearsal.');
    reject(!byPhase.has(report.phase),'Duplicate phase.');byPhase.set(report.phase,report);
    reject(report.runId===first.runId&&JSON.stringify(report.deployment)===JSON.stringify(first.deployment)&&JSON.stringify(report.dataset)===JSON.stringify(first.dataset),'Reports must use the same run, deployment/resources and observed dataset.');
    // Recompute checks from measurements; never trust an edited workloadPassed boolean.
    try{
      const reconstructed={kind:'campus-hosted-capacity-v1',runId:report.runId,base:'https://qualification.invalid',disposable:true,rehearsal:false,targetsApproved:report.targetsApproved,today:'2026-10-08',phases:{[report.phase]:report.workload},dataset:report.dataset,deployment:report.deployment};
      reject(safeSummary(reconstructed,report.phase,{metrics:report.metrics}).workloadPassed,'A workload or latency/error/drop gate failed.');
    }catch{reasons.push('Invalid measurement report.');}
  }
  for(const [name,rate,duration,foreground,preferences] of [['sustained',1500,1800,700,400],['burst',2000,300,1000,600],['soak',1500,14400,700,400]]){
    const p=byPhase.get(name)?.workload;
    reject(p&&p.foregroundRate+p.preferencesRate+p.authRate*4>=rate&&p.foregroundRate>=foreground&&p.authRate>=100&&p.preferencesRate>=preferences&&p.durationSeconds>=duration,`${name} needs the planned rate, foreground/password/polling mix and duration.`);
  }
  const d=first?.dataset;
  reject(d&&d.accounts>=300000&&d.applications>=60000000&&d.stages>=180000000&&d.largestHistory>=10000,'Representative population/history inventory is missing.');
  return {kind:'campus-capacity-acceptance-v1',accepted:reasons.length===0,reasons:[...new Set(reasons)],
    scope:'HTTP capacity only, conditional on the supplied inventory and resource evidence. Availability, failover, PITR, browser latency and provider/worker recovery require separate rehearsals.'};
}
export function validateRehearsal(report,exitCode){
  requireValue(report.kind==='campus-capacity-result-v1'&&report.mode==='rehearsal'&&report.targetsApproved===false,'A functional rehearsal must not claim hosted approval.');
  requireValue(exitCode===(report.workloadPassed?0:99),'k6 exit status must match the strict capacity verdict.');
  requireValue(report.metrics.unexpected_status?.values.rate===0,'Functional requests had unexpected outcomes.');
  requireValue((report.metrics.auth_completed?.values.count??0)>=1,'A real successful authentication workflow is required.');
  for(const route of apiRoutes){
    requireValue((report.metrics[`route_requests{route:${route}}`]?.values.count??0)>=1,`Missing functional route coverage: ${route}.`);
    requireValue((report.metrics[`api_latency{route:${route}}`]?.values.count??0)>=1,`Missing actual latency samples: ${route}.`);
  }
}
