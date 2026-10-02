// Run against a dedicated temporary Campus backend; never point this at normal user data.
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
const origin=process.argv[2] ?? 'http://127.0.0.1:18082';
const url=new URL(origin);
assert.ok(['127.0.0.1','localhost'].includes(url.hostname),'Use a loopback temporary test backend.');
let cookie='',csrf;
async function http(path,method='GET',body,version) {
  const headers={Accept:'application/json'};
  if(cookie)headers.Cookie=cookie;
  if(method!=='GET')headers[csrf.headerName]=csrf.token;
  if(version!==undefined)headers['If-Match']=String(version);
  if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch(origin+'/api'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
  for(const value of response.headers.getSetCookie())if(value.startsWith('JSESSIONID='))cookie=value.split(';')[0];
  const data=await response.json();assert.ok(response.ok,method+' '+path+': '+JSON.stringify(data));return data;
}
csrf=await http('/auth/csrf');
const resume=process.argv.includes('--resume');
const addSource=process.argv.includes('--add-source');
const account=resume || addSource ? JSON.parse(await readFile('test-results/reminder-browser-account.json','utf8'))
  : {email:'reminder-browser-'+Date.now()+'@example.test',password:'Campus-reminders-123!'};
const {email,password}=account;
if(!resume && !addSource)await http('/auth/register','POST',{email,password,displayName:'Reminder Preview',includeSamples:false});
const login=await fetch(origin+'/api/auth/login',{method:'POST',headers:{Cookie:cookie,[csrf.headerName]:csrf.token,
  'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({email,password})});
assert.ok(login.ok,'Synthetic login');for(const value of login.headers.getSetCookie())if(value.startsWith('JSESSIONID='))cookie=value.split(';')[0];
csrf=await http('/auth/csrf');
if(resume){
  const saved=JSON.parse(await readFile('test-results/reminder-browser-state.json','utf8'));
  const current=await http('/reminders');assert.deepEqual(current.preferences,saved.preferences);
  assert.deepEqual(current.reminders.map(r=>({id:r.id,dueAt:r.dueAt,snoozed:r.snoozed})),saved.reminders);
  console.log('Reminder restart smoke passed: preferences, occurrence identity and snooze time persisted.');
  process.exit(0);
}
const date=(offset=0)=>{const d=new Date();d.setUTCDate(d.getUTCDate()+offset);return d.toISOString().slice(0,10);};
if(addSource){
  await http('/applications','POST',{company:'Created in another tab',role:'Platform Intern',location:'Remote',status:'Saved',
    appliedDate:null,deadline:date(3),followUpDate:null,notes:'Synthetic cross-tab reminder link test',stages:[]});
  console.log('Synthetic application added without refreshing the browser workspace.');process.exit(0);
}
let app=await http('/applications','POST',{company:'Reminder Preview Labs',role:'Java Intern',location:'New York',status:'Interviewing',
  appliedDate:date(-1),deadline:null,followUpDate:date(),notes:'Synthetic reminder test',stages:[{id:null,name:'Technical interview',date:date(1),state:'Scheduled'}]});
await http('/applications','POST',{company:'Deadline Preview',role:'Engineering Intern',location:'Remote',status:'Saved',
  appliedDate:null,deadline:date(3),followUpDate:null,notes:'Synthetic saved opportunity',stages:[]});
let overview=await http('/reminders');assert.equal(overview.preferences.enabled,false);assert.equal(overview.emailAvailable,false);
const {version,...preferences}=overview.preferences;
overview=await http('/reminders/preferences','PUT',{...preferences,enabled:true},version);
assert.equal(overview.reminders.length,3);assert.equal(overview.readyCount,3);
const interview=overview.reminders.find(r=>r.kind==='INTERVIEW');
const snoozed=await http('/reminders/'+interview.id+'/snooze','POST',{minutes:60},interview.version);
assert.equal(snoozed.state,'PENDING');assert.ok(new Date(snoozed.dueAt)>new Date());
overview=await http('/reminders');assert.equal(overview.reminders.find(r=>r.id===interview.id).dueAt,snoozed.dueAt);
const followup=overview.reminders.find(r=>r.kind==='FOLLOW_UP');await http('/reminders/'+followup.id+'/dismiss','POST',undefined,followup.version);
const editable={company:app.company,role:app.role,location:app.location,status:app.status,appliedDate:app.appliedDate,
  deadline:null,followUpDate:null,notes:app.notes,stages:app.stages.map(s=>({...s,date:date(2)}))};
app=await http('/applications/'+app.id,'PUT',editable,app.version);
overview=await http('/reminders');assert.equal(overview.reminders.length,2);
assert.ok(!overview.reminders.some(r=>r.id===interview.id));
const {version:currentVersion,...current}=overview.preferences;
overview=await http('/reminders/preferences','PUT',{...current,deliveryMode:'DAILY_DIGEST',digestTime:'23:59'},currentVersion);
assert.equal(overview.preferences.deliveryMode,'DAILY_DIGEST');
const deadline=overview.reminders.find(r=>r.kind==='DEADLINE');
await http('/reminders/'+deadline.id+'/snooze','POST',{minutes:60},deadline.version);
overview=await http('/reminders');
await mkdir('test-results',{recursive:true});
await writeFile('test-results/reminder-browser-account.json',JSON.stringify({origin,email,password},null,2));
await writeFile('test-results/reminder-browser-state.json',JSON.stringify({preferences:overview.preferences,
  reminders:overview.reminders.map(r=>({id:r.id,dueAt:r.dueAt,snoozed:r.snoozed}))},null,2));
console.log('Reminder HTTP smoke passed: authentication, migration, settings, three source types, snooze persistence, dismissal, source rescheduling and digest settings.');
