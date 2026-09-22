// Owns application loading and mutations so views share one consistent server-backed dataset.
import { useCallback, useEffect, useRef, useState } from 'react';
import { applicationApi } from '../data/applicationApi';
import { ApiError } from '../data/api';
import { type Application, type ApplicationDraft } from '../domain/application';
export function useApplications() {
  const [apps,setApps]=useState<Application[]>([]);
  const [error,setError]=useState('');
  const [ready,setReady]=useState(false);
  const [busy,setBusy]=useState(false);
  // Use refs for synchronous write locking and for invalidating older in-flight reads.
  const mutation=useRef(false);
  const epoch=useRef(0);
  // Apply a response only if no newer load, mutation, or unmount has superseded it.
  const load=useCallback(async()=>{
    const current=++epoch.current;
    try {const list=await applicationApi.list();if(current===epoch.current){setApps(list);setReady(true);setError('');}}
    catch(e){if(current===epoch.current){setError(e instanceof Error?e.message:'Could not load applications.');setReady(false);}}
  },[]);
  // Load on mount and invalidate any pending result when the component leaves.
  useEffect(()=>{void load();return()=>{epoch.current++;};},[load]);
  // Serialize writes, refresh on version conflicts, and commit only confirmed server results.
  async function mutate<T>(operation:()=>Promise<T>,commit:(result:T)=>void):Promise<T> {
    if(mutation.current)throw new Error('Please wait for the current change to finish.');
    if(!ready)throw new Error('Refresh your workspace before making changes.');
    mutation.current=true;setBusy(true);epoch.current++;
    try {const result=await operation();commit(result);setError('');return result;}
    catch(e){if(e instanceof ApiError&&e.status===409)await load();throw e;}
    finally{mutation.current=false;setBusy(false);}
  }
  // Replace a changed record by ID while preserving the rest of the loaded history.
  const upsert=(app:Application)=>setApps(items=>[app,...items.filter(a=>a.id!==app.id)]);
  // Expose save, delete, and undo through the shared mutation lifecycle.
  async function save(draft:ApplicationDraft,id?:string,version?:number) {return mutate(()=>applicationApi.save(draft,id,version),upsert);}
  async function remove(app:Application) {return mutate(()=>applicationApi.remove(app),()=>setApps(items=>items.filter(a=>a.id!==app.id)));}
  async function restoreDeleted(app:Application) {return mutate(()=>applicationApi.restoreDeleted(app),upsert);}
  return {apps,error,ready,busy,save,remove,restoreDeleted,refresh:load};
}
