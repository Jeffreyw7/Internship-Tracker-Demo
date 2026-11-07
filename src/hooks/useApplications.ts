// Owns full-history loading, serialized writes, and durable import outcome recovery.
import {useCallback,useEffect,useRef,useState} from 'react';
import {applicationApi} from '../data/applicationApi';
import {ApiError,sessionGuard,assertCurrent} from '../data/api';
import {applicationImportApi} from '../data/applicationImportApi';
import {commitReviewedImport} from '../data/importCommitRecovery';
import {clearImportPointer,readImportPointer,writeImportPointer,type ImportPreview,type ImportReceipt,type ImportPointer} from '../domain/applicationImport';
import type {Application,ApplicationDraft} from '../domain/application';
import {applicationCreateApi,type CreateOutcome} from '../data/applicationCreateApi';
import {createFingerprint,readCreatePointer,rememberCreate,forgetCreate,type CreatePointer} from '../data/applicationCreateRecovery';
const tabStorage=()=>{try{return typeof sessionStorage==='undefined'?undefined:sessionStorage;}catch{return undefined;}};
export function useApplications(ownerId:string) {
  const [apps,setApps]=useState<Application[]>([]),[error,setError]=useState(''),[ready,setReady]=useState(false),[busy,setBusy]=useState(false);
  const [pendingImport,setPendingImport]=useState<ImportPointer|null>(()=>readImportPointer(tabStorage(),ownerId));
  const [pendingCreate,setPendingCreate]=useState<CreatePointer|null>(()=>readCreatePointer(tabStorage(),ownerId));
  const createPending=useRef(pendingCreate);
  const [importReceipt,setImportReceipt]=useState<ImportReceipt|null>(null),[importRefreshFailed,setImportRefreshFailed]=useState(false);
  const [recoveryPreview,setRecoveryPreview]=useState<ImportPreview|null>(null);
  const [retiredImport,setRetiredImport]=useState<{id:string;receiptMayHaveExpired:boolean}|null>(null);
  const mutation=useRef(false),pending=useRef(pendingImport),epoch=useRef(0),lifetime=useRef(0),loaded=useRef(false),latestRead=useRef(0);
  const guard=useCallback(()=>{const life=lifetime.current,account=sessionGuard();return ()=>life===lifetime.current&&account();},[]);
  const upsert=useCallback((app:Application)=>setApps(items=>{
    const known=items.find(a=>a.id===app.id);
    if(known&&known.version>app.version)return items;
    return app.deleted?items.filter(a=>a.id!==app.id):[app,...items.filter(a=>a.id!==app.id)];
  }),[]);
  const releaseCreate=useCallback(()=>{const pointer=createPending.current;if(pointer)forgetCreate(tabStorage(),pointer);createPending.current=null;setPendingCreate(null);},[]);
  const resolveCreate=useCallback((outcome:CreateOutcome)=>{if(outcome.status==='COMPLETED')upsert(outcome.application);releaseCreate();setError('');return outcome;},[releaseCreate,upsert]);
  const load=useCallback(async(parent:()=>boolean=()=>true)=>{
    const current=++epoch.current,alive=guard(),valid=()=>alive()&&parent()&&current===epoch.current;
    try {const list=await applicationApi.list(valid);assertCurrent(valid);setApps(list);loaded.current=true;setReady(true);setError('');return true;}
    catch(e){if(valid()){setError(e instanceof Error?e.message:'Could not load applications.');loaded.current=false;setReady(false);}return false;}
  },[guard]);
  const checkCreate=useCallback(async()=>{
    const pointer=createPending.current;if(!pointer||mutation.current)return null;
    const current=guard();mutation.current=true;setBusy(true);
    try {const outcome=await applicationCreateApi.status(pointer.key,current);assertCurrent(current);resolveCreate(outcome);if(outcome.status==='COMPLETED')await load(current);assertCurrent(current);return outcome;}
    catch(e){if(current())setError(e instanceof ApiError&&e.status===404?'Save completion is not confirmed. Retry the original save or cancel it before starting another.':e instanceof Error?e.message:'Could not check save status.');return null;}
    finally{if(current()){mutation.current=false;setBusy(false);}}
  },[guard,resolveCreate,load]);
  const releasePending=useCallback(()=>{pending.current=null;setPendingImport(null);setRecoveryPreview(null);clearImportPointer(tabStorage(),ownerId);},[ownerId]);
  const completeImport=useCallback(async(receipt:ImportReceipt,current:()=>boolean)=>{
    assertCurrent(current);setImportReceipt(receipt);setRetiredImport(null);releasePending();const refreshed=await load(current);if(current())setImportRefreshFailed(!refreshed);return receipt;
  },[load,releasePending]);
  const reconcileImport=useCallback(async()=>{
    const pointer=pending.current;if(!pointer||mutation.current)return;
    const current=guard();mutation.current=true;setBusy(true);
    try {
      let id=pointer.previewId;
      for(let hops=0;hops<10;hops++) {
        const status=await applicationImportApi.status(id,current);assertCurrent(current);
        if(status.status==='COMPLETED'){await completeImport(status.receipt,current);return;}
        if(status.status==='SUPERSEDED'){id=status.successorId;pending.current={ownerId,previewId:id};setPendingImport(pending.current);writeImportPointer(tabStorage(),ownerId,id);continue;}
        if(status.status==='PREVIEW')setRecoveryPreview(status.preview);
        setError('Import completion is not yet confirmed. Open Import CSV to check status.');return;
      }
      setError('Import status requires another check.');
    } catch(e) {
      if(current()){
        if(e instanceof ApiError&&[404,410].includes(e.status)&&e.authoritative){setRetiredImport({id:pointer.previewId,receiptMayHaveExpired:e.status===404});releasePending();}
        setError(e instanceof Error?e.message:'Could not check import status.');
      }
    } finally {if(current()){mutation.current=false;setBusy(false);}}
  },[completeImport,guard,ownerId,releasePending]);
  useEffect(()=>{mutation.current=false;void load();if(pending.current)void reconcileImport();return()=>{lifetime.current++;epoch.current++;};},[load,reconcileImport]);
  useEffect(()=>{if(createPending.current)void checkCreate();},[checkCreate]);
  useEffect(()=>{
    if(!pendingImport)return;let stopped=false,attempt=0,timer:ReturnType<typeof setTimeout>;
    const check=async()=>{if(stopped)return;await reconcileImport();if(!stopped&&++attempt<3)timer=setTimeout(()=>void check(),1000*2**attempt);};
    timer=setTimeout(()=>void check(),1000);return()=>{stopped=true;clearTimeout(timer);};
  },[pendingImport?.previewId,reconcileImport]);
  async function mutate<T>(operation:(current:()=>boolean)=>Promise<T>,commit:(result:T)=>void):Promise<T> {
    if(mutation.current||pending.current||createPending.current)throw new Error('Resolve the pending save or import, or wait for the current change to finish.');
    if(!ready)throw new Error('Refresh your workspace before making changes.');
    const current=guard();mutation.current=true;setBusy(true);epoch.current++;
    try {assertCurrent(current);const result=await operation(current);assertCurrent(current);commit(result);setError('');return result;}
    catch(e){if(current()&&e instanceof ApiError&&e.status===409)await load(current);throw e;}
    finally{if(current()){mutation.current=false;setBusy(false);}}
  }
  async function save(draft:ApplicationDraft,id?:string,version?:number){
    if(id)return mutate(current=>applicationApi.save(draft,id,version,current),upsert);
    if(mutation.current||pending.current)throw new Error('Wait for the current change or resolve the pending import.');
    if(!ready)throw new Error('Refresh your workspace before making changes.');
    const current=guard();mutation.current=true;setBusy(true);epoch.current++;
    let firstAttempt=false;
    try {
      const fingerprint=await createFingerprint(draft);assertCurrent(current);
      let pointer=createPending.current;
      if(pointer&&pointer.fingerprint!==fingerprint)throw new Error('This save is still unconfirmed. Check its status or cancel it before changing the draft.');
      if(!pointer){pointer={ownerId,key:crypto.randomUUID(),fingerprint};rememberCreate(tabStorage(),pointer);createPending.current=pointer;setPendingCreate(pointer);firstAttempt=true;}
      const result=await applicationCreateApi.create(draft,pointer.key,current);assertCurrent(current);
      upsert(result);releaseCreate();setError('');return result;
    } catch(e) {
      if(current()) {
        // Only a first, explicitly rejected request can release its reference without reconciliation.
        if(firstAttempt&&e instanceof ApiError&&[400,403,404,413,415,422,429].includes(e.status))releaseCreate();
        if(createPending.current)setError('Application save is not confirmed. Check its status or retry the same save.');
      }
      throw e;
    } finally {if(current()){mutation.current=false;setBusy(false);}}
  }
  async function cancelCreate(){
    const pointer=createPending.current;if(!pointer||mutation.current)throw new Error('Wait for the current request to finish.');
    const current=guard();mutation.current=true;setBusy(true);epoch.current++;
    try {const outcome=await applicationCreateApi.cancel(pointer.key,current);assertCurrent(current);resolveCreate(outcome);if(outcome.status==='COMPLETED')await load(current);assertCurrent(current);return outcome;}
    finally {if(current()){mutation.current=false;setBusy(false);}}
  }
  async function getLatest(id:string){
    const current=guard(),read=++latestRead.current;
    // Recovery must also restore a failed history refresh without closing the editor.
    if(!loaded.current&&!await load(current)){assertCurrent(current);throw new Error('Could not refresh your workspace. Check the latest saved version again to retry.');}
    assertCurrent(current);
    const started=epoch.current,canCommit=()=>read===latestRead.current&&started===epoch.current&&!mutation.current;
    try {const result=await applicationApi.get(id,current);assertCurrent(current);if(canCommit())upsert(result);return result;}
    catch(e){assertCurrent(current);if(e instanceof ApiError&&e.status===404){if(canCommit())setApps(items=>items.filter(app=>app.id!==id));return null;}throw e;}
  }
  async function remove(app:Application){return mutate(current=>applicationApi.remove(app,current),()=>setApps(items=>items.filter(a=>a.id!==app.id)));}
  async function restoreDeleted(app:Application){return mutate(current=>applicationApi.restoreDeleted(app,current),upsert);}
  async function commitImport(preview:ImportPreview,retry=false) {
    if(retiredImport)throw new Error('Acknowledge the retired review and start a fresh review before importing.');
    if(mutation.current||createPending.current||(!retry&&pending.current))throw new Error('Resolve the current change first.');
    if(retry&&pending.current?.previewId!==preview.id)throw new Error('Only the original reviewed import can be retried.');
    if(!ready||!preview.committable)throw new Error('Refresh and review the import before committing.');
    const current=guard();assertCurrent(current);mutation.current=true;setBusy(true);epoch.current++;
    try {return await commitReviewedImport({current,commit:applicationImportApi.commit,complete:completeImport,release:releasePending,remember:()=>{
      pending.current={ownerId,previewId:preview.id};setPendingImport(pending.current);writeImportPointer(tabStorage(),ownerId,preview.id);
    }},preview,retry);}
    catch(e){if(current()){
      setError(e instanceof Error?e.message:'Import completion is not yet confirmed.');
    }throw e;}
    finally {if(current()){mutation.current=false;setBusy(false);}}
  }
  async function retryImportRefresh(){const current=guard();const ok=await load(current);if(current())setImportRefreshFailed(!ok);}
  function acknowledgeRetiredImport(id:string,acknowledgeDuplicateRisk:boolean) {
    if(retiredImport?.id!==id||retiredImport.receiptMayHaveExpired&&!acknowledgeDuplicateRisk)return false;
    setRetiredImport(null);return true;
  }
  return {apps,error,ready,busy:busy||!!pendingImport||!!pendingCreate,requestBusy:busy,pendingImport,pendingCreate,checkCreate,cancelCreate,getLatest,recoveryPreview,retiredImport,acknowledgeRetiredImport,importReceipt,importRefreshFailed,commitImport,reconcileImport,retryImportRefresh,save,remove,restoreDeleted,refresh:()=>load(),clearImportRecovery:releasePending};
}
