import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { reminderApi, preferencePayload } from '../src/data/reminderApi.ts';
import { refreshCsrf, ApiError } from '../src/data/api.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const preferences={enabled:true,interviewsEnabled:true,deadlinesEnabled:false,followUpsEnabled:true,
  interviewLeadDays:1,deadlineLeadDays:3,followUpLeadDays:0,timezone:'America/New_York',reminderTime:'09:30',
  deliveryMode:'DAILY_DIGEST',digestTime:'10:00',emailEnabled:false,version:4};

test('preference payload includes configuration and excludes server metadata',()=>{
  const payload=preferencePayload({...preferences,ownerId:'forged',lastDigestDate:'2026-10-02'});
  assert.deepEqual(payload,{enabled:true,interviewsEnabled:true,deadlinesEnabled:false,followUpsEnabled:true,
    interviewLeadDays:1,deadlineLeadDays:3,followUpLeadDays:0,timezone:'America/New_York',reminderTime:'09:30',
    deliveryMode:'DAILY_DIGEST',digestTime:'10:00',emailEnabled:false});
});
test('preferences use session CSRF and optimistic version headers',async()=>{
  let call;
  globalThis.fetch=async(url,options)=>url==='/api/auth/csrf'?json({token:'csrf-reminder',headerName:'X-CSRF-TOKEN'}):
    (call={url,options},json({preferences}));
  await refreshCsrf();await reminderApi.savePreferences(preferences);
  assert.equal(call.url,'/api/reminders/preferences');assert.equal(call.options.method,'PUT');
  assert.equal(call.options.headers.get('If-Match'),'4');assert.equal(call.options.headers.get('X-CSRF-TOKEN'),'csrf-reminder');
  assert.equal(call.options.credentials,'same-origin');assert.deepEqual(JSON.parse(call.options.body),preferencePayload(preferences));
});
test('snooze and dismiss target only the reminder resource and preserve version',async()=>{
  const calls=[];
  globalThis.fetch=async(url,options)=>url==='/api/auth/csrf'?json({token:'csrf-reminder',headerName:'X-CSRF-TOKEN'}):
    (calls.push({url,options}),json({id:'reminder-1',version:8}));
  await refreshCsrf();await reminderApi.snooze({id:'reminder-1',version:7},60);await reminderApi.dismiss({id:'reminder-1',version:8});
  assert.equal(calls[0].url,'/api/reminders/reminder-1/snooze');assert.equal(calls[0].options.headers.get('If-Match'),'7');
  assert.deepEqual(JSON.parse(calls[0].options.body),{minutes:60});assert.equal(calls[1].url,'/api/reminders/reminder-1/dismiss');
  assert.equal(calls[1].options.headers.get('If-Match'),'8');assert.equal(calls[1].options.method,'POST');
});
test('conflicts surface without automatically replaying reminder writes',async()=>{
  let writes=0;
  globalThis.fetch=async(url)=>url==='/api/auth/csrf'?json({token:'csrf-reminder',headerName:'X-CSRF-TOKEN'}):
    (writes++,json({message:'Refresh before editing'},409));
  await refreshCsrf();await assert.rejects(()=>reminderApi.savePreferences(preferences),e=>e instanceof ApiError&&e.status===409);
  assert.equal(writes,1);
});
