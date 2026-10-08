import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createWorkspaceController, completeWorkspaceActivity} from '../src/domain/workspaceController.ts';
import {ApiError} from '../src/data/api.ts';
import {sampleApplications} from './fixtures/seed.ts';

const page=(id='row',next=null)=>({content:[{id,company:'Test'}],nextCursor:next,hasNext:next!==null,size:8,readAt:'2026-10-04T00:00:00Z'});
const summary={total:100,active:80,interviews:2,offers:1,due:3,scheduledInterviews:5,hasSamples:false,perStatus:{},readAt:'2026-10-04T00:00:00Z'};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const flush=async()=>{for(let i=0;i<5;i++)await Promise.resolve();};
function fixture(overrides={}) {
  const calls={list:[],summary:0,activities:[],detail:[]};
  let scheduled;
  const api={list:async filters=>{calls.list.push(filters);return page('row',String(Number(filters.cursor??0)+1));},summary:async()=>{calls.summary++;return summary;},activities:async filters=>{calls.activities.push(filters);return page();},detail:async id=>{calls.detail.push(id);return {...sampleApplications()[0],id};},...overrides};
  const controller=createWorkspaceController({api,today:()=> '2026-11-01',schedule:(fn,delay)=>{scheduled={fn,delay};return 1;},cancel:()=>{scheduled=undefined;}});
  return {controller,calls,runTimer:()=>{const next=scheduled;scheduled=undefined;next?.fn();},timer:()=>scheduled};
}
test('initial workspace fetches one page and account summary, then debounces search for 300ms',async()=>{
  const {controller,calls,runTimer,timer}=fixture();await controller.refresh();
  assert.equal(calls.list.length,1);assert.equal(calls.summary,1);assert.equal(calls.activities.length,0);
  controller.setFilters({...controller.getSnapshot().filters,q:'a'});controller.setFilters({...controller.getSnapshot().filters,q:'systems'});await flush();
  assert.equal(calls.list.length,1);assert.equal(timer().delay,300);runTimer();await flush();
  assert.equal(calls.list.length,2);assert.equal(calls.list[1].q,'systems');assert.equal(calls.summary,1);
});
test('switching to an activity feed clears application pagination state',async()=>{
  const {controller}=fixture();await controller.refresh();assert.equal(controller.getSnapshot().canNext,true);
  controller.setFilters({kind:'Interview',size:25});await flush();
  assert.equal(controller.getSnapshot().page,null);assert.equal(controller.getSnapshot().canNext,false);
  assert.equal(controller.getSnapshot().canPrevious,false);
});
test('previous/next uses cursors and bounds page history/cache to 20 and details to 100',async()=>{
  const {controller,calls}=fixture();await controller.refresh();
  for(let i=0;i<25;i++)await controller.next();
  assert.equal(controller.getSnapshot().pageNumber,26);assert.equal(controller.getSnapshot().cachedPages,20);
  const requests=calls.list.length;await controller.previous();assert.equal(calls.list.length,requests);
  assert.equal(controller.getSnapshot().pageNumber,25);
  for(let i=0;i<101;i++)await controller.loadDetail(String(i));
  assert.equal(controller.getSnapshot().details.size,100);assert.equal(controller.getSnapshot().details.has('0'),false);
  controller.setFilters({status:'Offer'});await flush();assert.equal(controller.getSnapshot().pageNumber,1);assert.equal(controller.getSnapshot().canPrevious,false);
});
test('account clear immediately drops caches and suppresses delayed page, detail and error responses',async()=>{
  const pendingPage=deferred(),pendingDetail=deferred();
  const {controller}=fixture({list:()=>pendingPage.promise,detail:()=>pendingDetail.promise});
  const read=controller.refresh();const detail=controller.loadDetail('old');const failed=assert.rejects(detail,/earlier workspace/);
  controller.clearAccount();assert.equal(controller.getSnapshot().page,null);assert.equal(controller.getSnapshot().summary,null);
  pendingPage.reject(new ApiError(401,'Old account'));pendingDetail.resolve(sampleApplications()[0]);await read;await failed;
  assert.equal(controller.getSnapshot().error,'');assert.equal(controller.getSnapshot().details.size,0);
});
test('mutations lock synchronously, refresh confirmed results and conflicts, and never replay writes',async()=>{
  const {controller,calls}=fixture();await controller.refresh();const pending=deferred();let writes=0;
  const write=controller.mutate(async()=>{writes++;return pending.promise;});
  await assert.rejects(()=>controller.mutate(async()=>{writes++;return sampleApplications()[0];}),/wait/i);
  pending.resolve(sampleApplications()[0]);await write;assert.equal(writes,1);assert.equal(calls.list.length,2);
  await assert.rejects(()=>controller.mutate(async()=>{writes++;throw new ApiError(409,'Changed elsewhere');}),e=>e.status===409);
  assert.equal(writes,2);assert.equal(calls.list.length,3);assert.equal(controller.getSnapshot().busy,false);
});
test('activity completion fetches full current detail; failed or changed detail never saves a draft',async()=>{
  const full=sampleApplications()[0];let writes=0;
  const {controller,calls}=fixture({detail:async()=>{calls.detail.push(full.id);return {...full,version:9};}});await controller.refresh();
  const activity={applicationId:full.id,applicationVersion:9,kind:'Interview',stageId:full.stages.find(s=>s.state==='Scheduled').id};
  const result=await completeWorkspaceActivity(controller,activity,async(draft,id,version)=>{writes++;assert.equal(version,9);assert.equal(draft.notes,full.notes);assert.equal(draft.stages.find(s=>s.id===activity.stageId).state,'Completed');return {...full,version:10};});
  assert.equal(result.before.version,9);assert.equal(writes,1);
  const broken=fixture({detail:async()=>{throw new ApiError(404,'Gone');}});await broken.controller.refresh();
  await assert.rejects(()=>completeWorkspaceActivity(broken.controller,activity,async()=>{writes++;}),/Gone/);
  const changed=fixture({detail:async()=>({...full,stages:[]})});await changed.controller.refresh();
  await assert.rejects(()=>completeWorkspaceActivity(changed.controller,activity,async()=>{writes++;}),/changed/i);
  assert.equal(writes,1);
});
test('changing filters across midnight refreshes the whole-account due summary',async()=>{
  let date='2026-11-01';const listDates=[],summaryDates=[];
  const controller=createWorkspaceController({today:()=>date,api:{
    list:async filters=>{listDates.push(filters.today);return page();},
    summary:async day=>{summaryDates.push(day);return {...summary,due:day==='2026-11-01'?3:6};},
    activities:async()=>page(),detail:async()=>sampleApplications()[0]
  }});
  await controller.refresh();date='2026-11-02';
  controller.setFilters({q:'new day'});await flush();
  assert.deepEqual(summaryDates,['2026-11-01','2026-11-02']);
  assert.deepEqual(listDates,['2026-11-01','2026-11-02']);
  assert.equal(controller.getSnapshot().summary.due,6);
});
for(const kind of ['Follow-up','Interview'])test(`outdated ${kind} completion refreshes and rejects instead of clearing a newer schedule`,async()=>{
  const full=sampleApplications()[0];const stage=full.stages.find(value=>value.state==='Scheduled');let writes=0;
  const {controller,calls}=fixture({detail:async()=>({...full,version:12,followUpDate:'2026-11-10',
    stages:full.stages.map(value=>value.id===stage.id?{...value,date:'2026-11-11'}:value)})});
  await controller.refresh();
  await assert.rejects(()=>completeWorkspaceActivity(controller,{applicationId:full.id,applicationVersion:11,kind,stageId:kind==='Interview'?stage.id:null},
    async()=>{writes++;return full;}),error=>error instanceof ApiError&&error.status===409);
  assert.equal(writes,0);assert.equal(calls.list.length,2);
});
