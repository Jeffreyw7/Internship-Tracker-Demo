import assert from 'node:assert/strict';
import fs from 'node:fs';
import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';
import {httpClient,delay} from '../support/distributedStack.mjs';

const routes=new Set(['list','summary','activities','detail','update','auth','export-create','export-status','export-download','csrf']);
export function percentile(samples,fraction){
  if(!samples.length)return null;
  const sorted=[...samples].sort((a,b)=>a-b);return sorted[Math.max(0,Math.ceil(sorted.length*fraction)-1)];
}
export function loadFixture(fixture,base){
  assert.equal(fixture.kind,'campus-disposable-load-v1','Only explicitly disposable fixtures are admitted.');
  const url=new URL(base);assert.ok(!url.username&&!url.password,'URL credentials are forbidden.');
  assert.ok(['http:','https:'].includes(url.protocol));assert.equal(url.origin,new URL(fixture.base).origin,'Fixture origin must match the explicit target origin.');
  assert.ok(Array.isArray(fixture.accounts)&&fixture.accounts.length>0&&fixture.accounts.length<=128,'Fixture needs 1..128 synthetic accounts.');
  for(const account of fixture.accounts){assert.match(account.email,/^load-[0-9]+@example\.test$/);assert.ok(account.password?.length>=12&&account.password.length<=128);}
  return fixture;
}
export async function runOpenLoop({rate,durationMs,maxConcurrency,maximumSamples=10_000,request,timeoutMs=12_000}){
  assert.ok(rate>=1&&rate<=10_000&&Number.isFinite(rate),'Rate must be 1..10000.');
  assert.ok(durationMs>=20&&durationMs<=21_600_000,'Duration must be 20ms..6h.');
  assert.ok(Number.isInteger(maxConcurrency)&&maxConcurrency>=1&&maxConcurrency<=1000);
  assert.ok(Number.isInteger(maximumSamples)&&maximumSamples>=1&&maximumSamples<=100_000);
  const result={rate,durationMs,maxConcurrency,scheduled:0,started:0,completed:0,dropped:0,schedulerDropped:0,capacityDropped:0,routes:{}};
  const active=new Set(),interval=1000/rate,total=Math.ceil(durationMs/interval),begin=performance.now();let fatal;
  const issue=()=>{
    const started=performance.now(),controller=new AbortController(),index=result.started-1;let timer,requestedRoute='list';
    const operation=Promise.race([
      Promise.resolve().then(()=>request({signal:controller.signal,index,markRoute:route=>{assert.ok(routes.has(route),'Unknown route template.');requestedRoute=route;}})),
      new Promise(resolve=>{timer=setTimeout(()=>{controller.abort();resolve({route:requestedRoute,status:0});},timeoutMs);})
    ]).catch(()=>({route:requestedRoute,status:0})).then(response=>{
      if(!routes.has(response.route)){fatal=new Error('Unknown route template.');return;}
      const stats=result.routes[response.route]??={count:0,errors:0,rejections:0,statuses:{},samples:[]};
      stats.count++;const status=Number.isInteger(response.status)&&response.status>=100&&response.status<=599?response.status:0;
      stats.statuses[status]=(stats.statuses[status]??0)+1;
      if(status===429)stats.rejections++;else if(status===0||status>=500)stats.errors++;
      const elapsed=performance.now()-started;
      // Reservoir sampling bounds memory throughout a soak without privileging early requests.
      if(stats.samples.length<maximumSamples)stats.samples.push(elapsed);
      else{const slot=Math.floor(Math.random()*stats.count);if(slot<maximumSamples)stats.samples[slot]=elapsed;}
    }).finally(()=>{clearTimeout(timer);result.completed++;active.delete(operation);});
    active.add(operation);
  };
  let next=0;
  while(next<total&&!fatal){
    const elapsed=performance.now()-begin,due=next*interval;
    if(elapsed<due){await delay(Math.min(20,due-elapsed));continue;}
    // Account for late arrivals instead of turning open-loop load into a queue.
    const missed=Math.min(total-next,Math.max(0,Math.floor((elapsed-due)/interval)));
    if(missed){next+=missed;result.scheduled+=missed;result.dropped+=missed;result.schedulerDropped+=missed;}
    if(next>=total)break;
    next++;result.scheduled++;
    if(active.size>=maxConcurrency){result.dropped++;result.capacityDropped++;continue;}
    result.started++;issue();
  }
  await Promise.all(active);
  if(fatal)throw fatal;
  for(const stats of Object.values(result.routes)){stats.p50=percentile(stats.samples,.5);stats.p95=percentile(stats.samples,.95);stats.p99=percentile(stats.samples,.99);stats.sampled=stats.count>stats.samples.length;}
  result.achievedArrivalsPerSecond=result.started/(durationMs/1000);
  result.elapsedMs=performance.now()-begin;
  return result;
}
export async function prepareSessions(base,fixture){
  loadFixture(fixture,base);
  const clients=[];
  for(const identity of fixture.accounts){
    const client=httpClient(base);await client.call('/api/auth/login',{method:'POST',form:true,data:identity,headers:await client.csrf()});
    const headers=await client.csrf(),page=await client.call('/api/v2/applications?size=8');
    const item=page.data.content[0],detail=item?(await client.call(`/api/applications/${item.id}`)).data:null;
    clients.push({client,identity,headers,item:detail,jobs:[]});
  }
  return clients;
}
export async function runHttpLoad({base,fixture,rate,durationMs,maxConcurrency,scenario='foreground',sessions}){
  loadFixture(fixture,base);assert.ok(['foreground','contention','auth'].includes(scenario));
  const clients=sessions??await prepareSessions(base,fixture);
  if(scenario==='contention')for(const account of clients.slice(-2))for(let i=0;i<2;i++){
    const {data:job}=await account.client.call('/api/v2/exports',{method:'POST',data:{},headers:account.headers,expected:202});account.jobs.push(job.id);
  }
  const today=new Date().toISOString().slice(0,10);
  let counter=0;
  return {...await runOpenLoop({rate,durationMs,maxConcurrency,request:async({signal,markRoute})=>{
    const index=counter++,account=clients[index%clients.length],choice=Math.floor(index/clients.length)%10;
    let route='list',url='/api/v2/applications?size=8',method='GET',body,headers={Cookie:[...account.client.cookies].map(([key,value])=>`${key}=${value}`).join('; ')};
    // Invalid-password auth pressure preserves these foreground sessions and exercises BCrypt/admission.
    if(scenario==='auth'){route='auth';url='/api/auth/login';method='POST';body=new URLSearchParams({...account.identity,password:'Synthetic-wrong-password-123!'}).toString();headers={...headers,...account.headers,'Content-Type':'application/x-www-form-urlencoded'};}
    else if(scenario==='contention'&&account.jobs.length&&choice<4){route='export-status';url=`/api/v2/exports/${account.jobs[choice%account.jobs.length]}`;}
    else if(choice===5||choice===6){route='summary';url=`/api/v2/applications/summary?today=${today}`;}
    else if(choice===7){route='activities';url='/api/v2/activities?size=25';}
    else if(choice===8&&account.item){route='detail';url=`/api/applications/${account.item.id}`;}
    else if(choice===9&&account.item){
      const item=account.item;route='update';url=`/api/applications/${item.id}`;method='PUT';
      body=JSON.stringify({company:item.company,role:item.role,location:item.location,status:item.status,appliedDate:item.appliedDate||null,
        deadline:item.deadline||null,followUpDate:item.followUpDate||null,notes:`Synthetic mutation ${index}`,stages:item.stages.map(stage=>({id:stage.id,name:stage.name,date:stage.date||null,state:stage.state}))});
      headers={...headers,...account.headers,'Content-Type':'application/json','If-Match':String(item.version)};
    }
    markRoute(route);
    const response=await fetch(base+url,{method,headers,body,signal,redirect:'manual'});
    const text=await consumeResponse(response,route==='detail'||route==='update');
    if(response.status===200&&(route==='detail'||route==='update'))account.item=JSON.parse(text);
    return {route,status:response.status};
  }}),scenario,accounts:clients.length};
}
export async function consumeResponse(response,keep=false,maximum=2_097_152){
  if(!response.body)return '';
  const reader=response.body.getReader(),chunks=[];let total=0;
  try{while(true){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>maximum)throw new Error('Response exceeds load-client bound.');if(keep)chunks.push(value);}}
  finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
  return keep?Buffer.concat(chunks).toString('utf8'):'';
}
function argumentsOf(values){const result={};for(let i=0;i<values.length;i+=2){assert.ok(values[i]?.startsWith('--')&&values[i+1],'Use --name value arguments.');result[values[i].slice(2)]=values[i+1];}return result;}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{
    const args=argumentsOf(process.argv.slice(2));assert.ok(args.base&&args.fixture&&args.out,'Supply --base --fixture --out.');
    assert.ok(fs.statSync(args.fixture).size<=256_000,'Fixture exceeds its bound.');
    const fixture=JSON.parse(fs.readFileSync(args.fixture,'utf8'));
    const report=await runHttpLoad({base:args.base,fixture,rate:Number(args.rate??20),durationMs:Number(args.duration??30)*1000,maxConcurrency:Number(args.concurrency??32),scenario:args.scenario??'foreground'});
    fs.writeFileSync(args.out,JSON.stringify(report,null,2));console.log(`Completed ${report.completed}/${report.scheduled} offered arrivals; ${report.dropped} dropped. Report saved.`);
  }catch(error){console.error(error.message);process.exitCode=1;}
}
