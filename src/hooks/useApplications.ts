// Owns full-history loading, serialized writes, and durable import outcome recovery.
import {useCallback,useEffect,useRef,useState} from 'react';
import {applicationApi} from '../data/applicationApi';
import {ApiError,sessionGuard,assertCurrent} from '../data/api';
import {applicationImportApi} from '../data/applicationImportApi';
import {commitReviewedImport} from '../data/importCommitRecovery';
import {clearImportPointer,readImportPointer,writeImportPointer,type ImportPreview,type ImportReceipt,type ImportPointer} from '../domain/applicationImport';
import type {Application,ApplicationDraft} from '../domain/application';
const tabStorage=()=>{try{return typeof sessionStorage==='undefined'?undefined:sessionStorage;}catch{return undefined;}};
export function useApplications(ownerId:string) {
  const [apps,setApps]=useState<Application[]>([]),[error,setError]=useState(''),[ready,setReady]=useState(false),[busy,setBusy]=useState(false);
  const [pendingImport,setPendingImport]=useState<ImportPointer|null>(()=>readImportPointer(tabStorage(),ownerId));
  const [importReceipt,setImportReceipt]=useState<ImportReceipt|null>(null),[importRefreshFailed,setImportRefreshFailed]=useState(false);
  const [recoveryPreview,setRecoveryPreview]=useState<ImportPreview|null>(null);
  const [retiredImport,setRetiredImport]=useState<{id:string;receiptMayHaveExpired:boolean}|null>(null);
  const mutation=useRef(false),pending=useRef(pendingImport),epoch=useRef(0),lifetime=useRef(0);
  const guard=useCallback(()=>{const life=lifetime.current,account=sessionGuard();return ()=>life===lifetime.current&&account();},[]);
  const load=useCallback(async(parent:()=>boolean=()=>true)=>{
    const current=++epoch.current,alive=guard(),valid=()=>alive()&&parent()&&current===epoch.current;
    try {const list=await applicationApi.list(valid);assertCurrent(valid);setApps(list);setReady(true);setError('');return true;}
    catch(e){if(valid()){setError(e instanceof Error?e.message:'Could not load applications.');setReady(false);}return false;}
  },[guard]);
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
  useEffect(()=>{
    if(!pendingImport)return;let stopped=false,attempt=0,timer:ReturnType<typeof setTimeout>;
    const check=async()=>{if(stopped)return;await reconcileImport();if(!stopped&&++attempt<3)timer=setTimeout(()=>void check(),1000*2**attempt);};
    timer=setTimeout(()=>void check(),1000);return()=>{stopped=true;clearTimeout(timer);};
  },[pendingImport?.previewId,reconcileImport]);
  async function mutate<T>(operation:(current:()=>boolean)=>Promise<T>,commit:(result:T)=>void):Promise<T> {
    if(mutation.current||pending.current)throw new Error('Resolve the pending import or wait for the current change to finish.');
    if(!ready)throw new Error('Refresh your workspace before making changes.');
    const current=guard();mutation.current=true;setBusy(true);epoch.current++;
    try {assertCurrent(current);const result=await operation(current);assertCurrent(current);commit(result);setError('');return result;}
    catch(e){if(current()&&e instanceof ApiError&&e.status===409)await load(current);throw e;}
    finally{if(current()){mutation.current=false;setBusy(false);}}
  }
  const upsert=(app:Application)=>setApps(items=>[app,...items.filter(a=>a.id!==app.id)]);
  async function save(draft:ApplicationDraft,id?:string,version?:number){return mutate(current=>applicationApi.save(draft,id,version,current),upsert);}
  async function remove(app:Application){return mutate(current=>applicationApi.remove(app,current),()=>setApps(items=>items.filter(a=>a.id!==app.id)));}
  async function restoreDeleted(app:Application){return mutate(current=>applicationApi.restoreDeleted(app,current),upsert);}
  async function commitImport(preview:ImportPreview,retry=false) {
    if(retiredImport)throw new Error('Acknowledge the retired review and start a fresh review before importing.');
    if(mutation.current||(!retry&&pending.current))throw new Error('Resolve the current change first.');
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
  return {apps,error,ready,busy:busy||!!pendingImport,requestBusy:busy,pendingImport,recoveryPreview,retiredImport,acknowledgeRetiredImport,importReceipt,importRefreshFailed,commitImport,reconcileImport,retryImportRefresh,save,remove,restoreDeleted,refresh:()=>load(),clearImportRecovery:releasePending};
}
