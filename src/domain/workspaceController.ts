import type {Application, ApplicationDraft} from './application.ts';
import type {ActivityItem, ActivityKind, ApplicationFilters, ApplicationListItem, CursorPage, WorkspaceSummary, workspaceApi} from '../data/workspaceApi.ts';
import {ApiError} from '../data/api.ts';

export interface WorkspaceFilters extends Omit<ApplicationFilters,'cursor'> {kind?:ActivityKind}
export interface WorkspaceSnapshot {
  filters:WorkspaceFilters;page:CursorPage<ApplicationListItem>|null;activityPage:CursorPage<ActivityItem>|null;
  summary:WorkspaceSummary|null;loading:boolean;summaryLoading:boolean;error:string;summaryError:string;
  busy:boolean;ready:boolean;pageNumber:number;canPrevious:boolean;canNext:boolean;cachedPages:number;
  details:ReadonlyMap<string,Application>;
}
interface Dependencies {
  api:typeof workspaceApi;today:()=>string;
  schedule?:(callback:()=>void,delay:number)=>unknown;cancel?:(handle:unknown)=>void;
}
type CachedPage={page:CursorPage<ApplicationListItem>|null;activityPage:CursorPage<ActivityItem>|null};
const message=(error:unknown)=>error instanceof Error?error.message:'Could not load your workspace.';
const stale=()=>new Error('This response belongs to an earlier workspace.');
function touch<K,V>(cache:Map<K,V>,key:K,value:V,limit:number) {
  cache.delete(key);cache.set(key,value);
  if(cache.size>limit)cache.delete(cache.keys().next().value!);
}
export function createWorkspaceController({api,today,schedule=(fn,ms)=>setTimeout(fn,ms),cancel=handle=>clearTimeout(handle as ReturnType<typeof setTimeout>)}:Dependencies) {
  const defaults=():WorkspaceFilters=>({q:'',tab:'all',dueOnly:false,today:today(),sort:'updated',size:8});
  let generation=0,readEpoch=0,summaryEpoch=0,detailEpoch=0,mutation=false;
  let history:(string|null)[]=[null],index=0,offset=0,timer:unknown;
  const listeners=new Set<()=>void>(),pages=new Map<string,CachedPage>(),details=new Map<string,Application>();
  const pendingDetails=new Map<string,{epoch:number;promise:Promise<Application>}>();
  let snapshot:WorkspaceSnapshot={filters:defaults(),page:null,activityPage:null,summary:null,loading:false,summaryLoading:false,error:'',summaryError:'',busy:false,ready:false,pageNumber:1,canPrevious:false,canNext:false,cachedPages:0,details:new Map()};
  function publish(patch:Partial<WorkspaceSnapshot>={}) {
    const next={...snapshot,...patch};
    snapshot={...next,pageNumber:offset+index+1,canPrevious:index>0,
      canNext:(next.page??next.activityPage)?.hasNext??false,
      cachedPages:pages.size,details:new Map(details)};
    for(const listener of listeners)listener();
  }
  function stopTimer() {if(timer!==undefined){cancel(timer);timer=undefined;}}
  function resetPages() {pages.clear();history=[null];index=0;offset=0;readEpoch++;}
  function clearDetails() {details.clear();pendingDetails.clear();detailEpoch++;}
  async function loadPage() {
    const token=++readEpoch,account=generation,filters={...snapshot.filters},cursor=history[index];
    const isCurrent=()=>account===generation&&token===readEpoch;
    const key=JSON.stringify([filters,cursor]);
    const cached=pages.get(key);
    if(cached){touch(pages,key,cached,20);publish({...cached,loading:false,ready:true,error:''});return;}
    publish({loading:true,error:'',ready:false,page:null,activityPage:null});
    try {
      const result:CachedPage=filters.kind
        ?{page:null,activityPage:await api.activities({kind:filters.kind,q:filters.q,today:filters.today,size:filters.size,cursor},isCurrent)}
        :{page:await api.list({...filters,cursor},isCurrent),activityPage:null};
      if(!isCurrent())return;
      touch(pages,key,result,20);publish({...result,loading:false,ready:true,error:''});
    } catch(error) {if(isCurrent())publish({loading:false,ready:false,error:message(error)});}
  }
  async function loadSummary() {
    const token=++summaryEpoch,account=generation;
    const isCurrent=()=>account===generation&&token===summaryEpoch;
    publish({summaryLoading:true,summaryError:''});
    try {const result=await api.summary(today(),isCurrent);if(isCurrent())publish({summary:result,summaryLoading:false});}
    catch(error){if(isCurrent())publish({summaryLoading:false,summaryError:message(error)});}
  }
  async function refresh() {
    stopTimer();resetPages();clearDetails();publish({filters:{...snapshot.filters,today:today()}});
    await Promise.all([loadPage(),loadSummary()]);
  }
  function setFilters(patch:Partial<WorkspaceFilters>) {
    const next={...snapshot.filters,...patch,today:today()};
    if(JSON.stringify(next)===JSON.stringify(snapshot.filters))return;
    const changed=(Object.keys(next) as (keyof WorkspaceFilters)[]).filter(key=>next[key]!==snapshot.filters[key]);
    const onlyQuery=changed.every(key=>key==='q');
    stopTimer();resetPages();publish({filters:next,page:null,activityPage:null,ready:false,loading:true,error:''});
    if(onlyQuery)timer=schedule(()=>{timer=undefined;void loadPage();},300);
    else void loadPage();
  }
  async function next() {
    if(snapshot.loading)return;
    const cursor=(snapshot.page??snapshot.activityPage)?.nextCursor;if(!cursor)return;
    history=history.slice(0,index+1);history.push(cursor);index++;
    if(history.length>20){history.shift();index--;offset++;}
    await loadPage();
  }
  async function previous() {if(index===0||snapshot.loading)return;index--;await loadPage();}
  function clearAccount() {
    generation++;summaryEpoch++;stopTimer();resetPages();clearDetails();mutation=false;
    publish({filters:defaults(),page:null,activityPage:null,summary:null,loading:false,summaryLoading:false,error:'',summaryError:'',busy:false,ready:false,canNext:false});
  }
  async function loadDetail(id:string,force=false):Promise<Application> {
    const cached=details.get(id);
    if(cached&&!force){touch(details,id,cached,100);publish();return cached;}
    const pending=pendingDetails.get(id);if(pending&&!force&&pending.epoch===detailEpoch)return pending.promise;
    const account=generation,epoch=detailEpoch;
    let marker:{epoch:number;promise:Promise<Application>};
    const isCurrent=()=>account===generation&&epoch===detailEpoch&&pendingDetails.get(id)===marker;
    const promise=Promise.resolve().then(async()=>{
      try {
        const result=await api.detail(id,isCurrent);
        if(!isCurrent())throw stale();
        touch(details,id,result,100);publish();return result;
      } catch(error){if(!isCurrent())throw stale();throw error;}
      finally {if(pendingDetails.get(id)===marker)pendingDetails.delete(id);}
    });
    marker={epoch,promise};
    pendingDetails.set(id,marker);return marker.promise;
  }
  async function mutate(operation:(isCurrent:()=>boolean)=>Promise<Application>):Promise<Application> {
    if(mutation)throw new Error('Please wait for the current change to finish.');
    if(!snapshot.ready)throw new Error('Refresh your workspace before making changes.');
    mutation=true;const account=generation;readEpoch++;summaryEpoch++;stopTimer();publish({busy:true});
    const isCurrent=()=>account===generation;
    try {
      const result=await operation(isCurrent);
      if(!isCurrent())throw stale();
      await refresh();if(!isCurrent())throw stale();return result;
    } catch(error) {
      if(!isCurrent())throw stale();
      if(error instanceof ApiError&&error.status===409)await refresh();
      throw error;
    } finally {if(isCurrent()){mutation=false;publish({busy:false});}}
  }
  return {subscribe:(listener:()=>void)=>{listeners.add(listener);return ()=>{listeners.delete(listener);};},getSnapshot:()=>snapshot,
    refresh,setFilters,next,previous,clearAccount,loadDetail,mutate};
}
export type WorkspaceController=ReturnType<typeof createWorkspaceController>;
export async function completeWorkspaceActivity(controller:WorkspaceController,activity:Pick<ActivityItem,'applicationId'|'kind'|'stageId'>,
    save:(draft:ApplicationDraft,id:string,version:number)=>Promise<Application>) {
  const before=await controller.loadDetail(activity.applicationId,true);
  let draft:ApplicationDraft;
  if(activity.kind==='Interview') {
    if(!before.stages.some(stage=>stage.id===activity.stageId&&stage.state==='Scheduled'))throw new Error('This interview changed. Refresh before completing it.');
    draft={...before,stages:before.stages.map(stage=>stage.id===activity.stageId?{...stage,state:'Completed'}:stage)};
  } else if(activity.kind==='Follow-up') {
    if(!before.followUpDate)throw new Error('This follow-up changed. Refresh before completing it.');
    draft={...before,followUpDate:''};
  } else throw new Error('Open the application to change its deadline.');
  const updated=await save(draft,before.id,before.version);return {before,updated};
}
