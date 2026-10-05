import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomBytes, randomUUID} from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const delay=milliseconds=>new Promise(resolve=>setTimeout(resolve,milliseconds));
export function command(executable,args,{env=process.env,timeout=900_000,input}={}) {
  return new Promise((resolve,reject)=>{
    const child=spawn(executable,args,{cwd:root,env,stdio:['pipe','pipe','pipe'],windowsHide:true});
    let output='', exceeded=false;
    const collect=data=>{if(output.length+data.length>16_000_000){exceeded=true;child.kill();}else output+=data;};
    child.stdout.on('data',collect); child.stderr.on('data',collect);
    const timer=setTimeout(()=>{child.kill();reject(new Error('Command deadline exceeded.'));},timeout);
    child.on('error',error=>{clearTimeout(timer);reject(error);});
    child.on('exit',code=>{clearTimeout(timer);resolve({code,output:exceeded?'Command output exceeded its bound.':output});});
    child.stdin.end(input);
  });
}
export async function fixtureStack({composeFile='compose.scalable.yaml'}={}) {
  const docker=process.env.CAMPUS_DOCKER_EXECUTABLE??'docker';
  const project=`campus-test-${randomUUID()}`;
  const directory=path.join(root,'backend','target',project);fs.mkdirSync(directory,{recursive:true});
  const server=net.createServer(); await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const port=server.address().port;await new Promise(resolve=>server.close(resolve));
  const secret=()=>randomBytes(32).toString('hex');
  const env={...process.env,COMPOSE_PROJECT_NAME:project,INGRESS_PORT:String(port),PUBLIC_ORIGIN:`http://127.0.0.1:${port}`,
    DATABASE_ADMIN_PASSWORD:secret(),MIGRATION_DATABASE_PASSWORD:secret(),DATABASE_PASSWORD:secret(),
    MAIL_WORKER_DATABASE_PASSWORD:secret(),EXPORT_WORKER_DATABASE_PASSWORD:secret(),REDIS_PASSWORD:secret(),
    AUTH_ADMISSION_HMAC_SECRET:secret(),VERIFICATION_RATE_LIMIT_SECRET:secret(),METRICS_TOKEN:secret(),
    OUTBOX_ENCRYPTION_KEY:randomBytes(32).toString('base64'),OUTBOX_KEY_ID:'disposable-capture',
    EXPORT_API_ACCESS_KEY:secret(),EXPORT_API_SECRET_KEY:secret(),EXPORT_WORKER_ACCESS_KEY:secret(),EXPORT_WORKER_SECRET_KEY:secret(),
    S3_CREDENTIALS_FILE:path.join(directory,'s3.json').replaceAll('\\','/')};
  // Desktop's credential helper lives beside its CLI; newly installed PATH can be stale.
  if(path.isAbsolute(docker))env.PATH=`${path.dirname(docker)}${path.delimiter}${env.PATH??''}`;
  // Pick an unused local subnet; the ingress is the only trusted forwarding peer.
  const networks=await command(docker,['network','ls','-q']);assert.equal(networks.code,0,'Docker engine must be running.');
  const ids=networks.output.trim().split(/\s+/).filter(Boolean);
  const existing=ids.length?await command(docker,['network','inspect',...ids]):{output:'[]',code:0};
  assert.equal(existing.code,0);const used=JSON.parse(existing.output).flatMap(n=>n.IPAM.Config??[]).map(c=>c.Subnet);
  let octet=60;while(used.some(s=>s===`172.30.${octet}.0/24`)){octet++;assert.ok(octet<250,'No unused test subnet.');}
  env.INGRESS_SUBNET=`172.30.${octet}.0/24`;env.INGRESS_ADDRESS=`172.30.${octet}.2`;
  env.INGRESS_DYNAMIC_RANGE=`172.30.${octet}.128/25`;
  env.TRUSTED_PROXY_REGEX=`^172\\.30\\.${octet}\\.2$`;
  fs.writeFileSync(env.S3_CREDENTIALS_FILE,JSON.stringify({identities:[
    {name:'api-download',credentials:[{accessKey:env.EXPORT_API_ACCESS_KEY,secretKey:env.EXPORT_API_SECRET_KEY}],actions:['Read:campus-exports']},
    {name:'export-worker',credentials:[{accessKey:env.EXPORT_WORKER_ACCESS_KEY,secretKey:env.EXPORT_WORKER_SECRET_KEY}],actions:['Read:campus-exports','Write:campus-exports','List:campus-exports']}
  ]}),{mode:0o600});
  const redact=text=>Object.entries(env).filter(([key])=>/PASSWORD|SECRET|KEY|TOKEN/.test(key)).reduce((text,[,value])=>value?text.replaceAll(value,'[redacted]'):text,text);
  const compose=async(args,options={})=>{
    const result=await command(docker,['compose','-f',path.join(root,composeFile),'-p',project,...args],{env,...options});
    if(!options.privateOutput)fs.appendFileSync(path.join(directory,'compose.log'),redact(result.output));return result;
  };
  const run=async(args,options={})=>{const result=await compose(args,options);assert.equal(result.code,0,redact(result.output.slice(-5000)));return result.output;};
  const config=async()=>JSON.parse(await run(['config','--format','json']));
  const dispose=async()=>{
    assert.match(project,/^campus-test-[a-f0-9-]{36}$/);
    // Only this newly generated project owns these volumes; never use the default project here.
    await run(['down','--volumes','--remove-orphans']);
    fs.rmSync(env.S3_CREDENTIALS_FILE,{force:true});
  };
  return {docker,project,directory,env,base:env.PUBLIC_ORIGIN,compose,run,config,redact,dispose,
    async inspect(service){const id=(await run(['ps','-a','-q',service])).trim();assert.ok(id,`Missing ${service} container`);
      const result=await command(docker,['inspect',id],{env});assert.equal(result.code,0);return JSON.parse(result.output)[0];},
    async manifest(){
      const result=[];
      for(const service of ['api-a','mail-worker','export-worker','migrate','ingress','database','redis','storage','mail']){
        const id=(await run(['ps','-a','-q',service])).trim();assert.ok(id);
        const container=JSON.parse((await command(docker,['inspect',id],{env})).output)[0];
        const metadata=JSON.parse((await command(docker,['image','inspect',container.Image],{env})).output)[0];
        result.push({service,imageId:metadata.Id,registryDigests:metadata.RepoDigests,architecture:metadata.Architecture,os:metadata.Os});
      }
      fs.writeFileSync(path.join(directory,'release-manifest.json'),JSON.stringify(result,null,2));return result;
    },
    async sql(sql){return run(['exec','-T','database','psql','-X','-U','campus_admin','-d','campus','-v','ON_ERROR_STOP=1','-At'],{input:sql,privateOutput:true});},
    async mail(){return JSON.parse(await run(['exec','-T','mail','wget','-qO-','http://127.0.0.1:8025/api/v1/messages'],{privateOutput:true}));},
    async mailBody(id){return run(['exec','-T','mail','wget','-qO-',`http://127.0.0.1:8025/api/v1/message/${id}/raw`],{privateOutput:true});}
  };
}
export function assertTopology(configuration){
  const services=configuration.services;
  assert.ok(services['api-a']&&services['api-b'],'Two separate API instances are required.');
  for(const [name,service] of Object.entries(services)){
    if(name==='ingress'){assert.equal(service.ports.length,1);assert.equal(service.ports[0].host_ip,'127.0.0.1');}
    else assert.equal(service.ports?.length??0,0,`${name} must have no published host port.`);
  }
  for(const name of ['api-a','api-b','mail-worker','export-worker']){
    assert.equal(services[name].depends_on.migrate.condition,'service_completed_successfully');
    assert.ok(services[name].mem_limit,`${name} needs a memory budget.`);
  }
  assert.equal(configuration.networks.data.internal,true);
}
export function httpClient(base){
  const cookies=new Map();
  const call=async(route,{method='GET',data,headers={},expected=200,form=false}={})=>{
    if(cookies.size)headers={...headers,Cookie:[...cookies].map(([key,value])=>`${key}=${value}`).join('; ')};
    let body;if(data!==undefined){body=form?new URLSearchParams(data).toString():JSON.stringify(data);headers={...headers,'Content-Type':form?'application/x-www-form-urlencoded':'application/json'};}
    const response=await fetch(base+route,{method,headers,body,redirect:'manual',signal:AbortSignal.timeout(12_000)});
    for(const value of response.headers.getSetCookie()){const pair=value.split(';',1)[0],separator=pair.indexOf('=');cookies.set(pair.slice(0,separator),pair.slice(separator+1));}
    const raw=await response.text();assert.equal(response.status,expected,`${method} ${route}: ${raw.slice(0,250)}`);
    return {data:response.headers.get('content-type')?.includes('application/json')&&raw?JSON.parse(raw):raw,headers:response.headers,status:response.status};
  };
  return {call,cookies,async csrf(){const {data}=await call('/api/auth/csrf');return {[data.headerName]:data.token};}};
}
export async function until(check,description,timeout=120_000){
  const end=Date.now()+timeout;let error;
  while(Date.now()<end){try{const result=await check();if(result)return result;}catch(e){error=e;}await delay(500);}
  throw new Error(`Timed out: ${description}${error?`: ${error.message}`:''}`);
}
