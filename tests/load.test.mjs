import assert from 'node:assert/strict';
import test from 'node:test';
import {createServer} from 'node:http';
import {runOpenLoop,percentile,loadFixture,consumeResponse} from './load/run.mjs';
import {historyCounts,fixtureSql} from './load/seed.mjs';

test('offered arrivals stay fixed when a slow real HTTP server overloads the client ceiling',async()=>{
  let active=0,peak=0;
  const server=createServer((request,response)=>{active++;peak=Math.max(peak,active);setTimeout(()=>{active--;response.end('ok');},80);});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const base=`http://127.0.0.1:${server.address().port}`;
    const result=await runOpenLoop({rate:100,durationMs:200,maxConcurrency:2,maximumSamples:2,
      request:async({signal})=>{const response=await fetch(base,{signal});await response.text();return {route:'list',status:response.status};}});
    assert.equal(result.scheduled,20);assert.equal(result.completed,result.started);assert.equal(result.started+result.dropped,20);
    assert.equal(result.routes.list.errors,0,'The local HTTP fixture must be reachable.');
    assert.ok(result.dropped>=10);assert.ok(peak<=2);assert.ok(result.routes.list.samples.length<=2);
    assert.ok(result.routes.list.p95>=60,'Latency must reflect actual server delay.');
  }finally{await new Promise(resolve=>server.close(resolve));}
});
test('bounded sampling, known route templates, rejection/error counts and no credentials in a report',async()=>{
  let count=0;
  const result=await runOpenLoop({rate:40,durationMs:150,maxConcurrency:4,maximumSamples:2,
    request:async()=>({route:++count%2?'auth':'list',status:count%2?429:503,token:'secret-do-not-report',password:'fixture-password',url:'https://private?token=secret'})});
  assert.equal(result.scheduled,6);assert.equal(result.routes.auth.rejections,3);assert.equal(result.routes.list.errors,3);
  assert.ok(result.routes.auth.samples.length<=2);assert.ok(result.routes.list.samples.length<=2);
  assert.doesNotMatch(JSON.stringify(result),/secret|password|private/);
  assert.equal(percentile([10,30,20,50,40],0.95),50);assert.equal(percentile([],0.95),null);
  await assert.rejects(runOpenLoop({rate:1,durationMs:20,maxConcurrency:1,request:async()=>({route:'/api/guessed?token=secret',status:200})}),/Unknown route/);
});
test('fixture requires explicit disposable origin, bounded synthetic identities, and never accepts embedded URL credentials',()=>{
  const fixture={kind:'campus-disposable-load-v1',base:'http://127.0.0.1:12345',accounts:[{email:'load-1@example.test',password:'Synthetic-only-123!'}]};
  assert.equal(loadFixture(fixture,fixture.base).accounts.length,1);
  assert.throws(()=>loadFixture({...fixture,kind:'production'},fixture.base),/disposable/);
  assert.throws(()=>loadFixture(fixture,'http://example.com'),/origin/);
  assert.throws(()=>loadFixture({...fixture,base:'http://user:pass@localhost'},'http://user:pass@localhost'),/credentials/);
  assert.throws(()=>loadFixture({...fixture,accounts:Array(129).fill(fixture.accounts[0])},fixture.base),/128/);
});
test('synthetic seed is skewed and SQL admits only bounded generated inputs',()=>{
  assert.deepEqual(historyCounts(5,20,300),[20,20,20,20,300]);
  const sql=fixtureSql({accounts:[{id:'11111111-1111-4111-8111-111111111111',email:'load-1@example.test',count:20}],passwordHash:'$2a$10$synthetic.hash',today:'2026-10-05'});
  assert.match(sql,/generate_series\(1,20\)/);assert.match(sql,/sample/);assert.match(sql,/interview_stages/);
  assert.throws(()=>fixtureSql({accounts:[{id:"');DROP TABLE users;--",email:'load-1@example.test',count:20}],passwordHash:'unsafe',today:'2026-10-05'}),/fixture/);
});
test('a hung auth request retains its route and is timed out without queueing or replay',async()=>{
  let issued=0;
  const report=await runOpenLoop({rate:100,durationMs:30,maxConcurrency:1,timeoutMs:15,request:async({markRoute})=>{issued++;markRoute('auth');return new Promise(()=>{});}});
  assert.equal(report.scheduled,3);assert.equal(issued,report.started);assert.equal(report.completed,report.started);
  assert.equal(report.routes.auth.errors,report.started);assert.equal(report.routes.list,undefined);
});
test('response consumption enforces a byte limit and cancels the reader',async()=>{
  let cancelled=false;
  const response={body:new ReadableStream({pull(controller){controller.enqueue(new Uint8Array(32));},cancel(){cancelled=true;}})};
  await assert.rejects(consumeResponse(response,false,64),/bound/);assert.equal(cancelled,true);
});
