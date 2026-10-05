import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {workspaceApi} from '../src/data/workspaceApi.ts';
const original=globalThis.fetch;
afterEach(()=>{globalThis.fetch=original;});
test('workspace adapters request only one bounded page and encode literal search/cursors',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify({content:[],hasNext:true,nextCursor:'next',size:8,readAt:'now'}),{headers:{'Content-Type':'application/json'}});};
  await workspaceApi.list({q:'%_ & systems',status:'Offer',tab:'closed',dueOnly:true,today:'2026-11-01',sort:'updated',size:8,cursor:'opaque'});
  assert.equal(calls.length,1);const url=new URL(calls[0].url,'http://localhost');
  assert.equal(url.pathname,'/api/v2/applications');assert.equal(url.searchParams.get('q'),'%_ & systems');assert.equal(url.searchParams.get('cursor'),'opaque');
  assert.equal(calls[0].options.credentials,'same-origin');assert.equal(calls[0].options.cache,'no-store');
  await workspaceApi.summary('2026-11-01');await workspaceApi.activities({kind:'Interview',q:'Systems',size:25});await workspaceApi.detail('detail-id');
  assert.equal(calls[1].url,'/api/v2/applications/summary?today=2026-11-01');assert.ok(calls[2].url.startsWith('/api/v2/activities?'));assert.equal(calls[3].url,'/api/applications/detail-id');
});
