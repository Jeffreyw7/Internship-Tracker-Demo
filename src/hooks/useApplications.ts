// Keeps serialized application mutations with the hook; the controller bounds reads and caches.
import {useEffect, useState, useSyncExternalStore} from 'react';
import {applicationApi} from '../data/applicationApi';
import {workspaceApi} from '../data/workspaceApi';
import {today, type Application, type ApplicationDraft} from '../domain/application';
import {createWorkspaceController} from '../domain/workspaceController';
export function useApplications(accountId:string) {
  const [controller]=useState(()=>createWorkspaceController({api:workspaceApi,today}));
  const workspace=useSyncExternalStore(controller.subscribe,controller.getSnapshot,controller.getSnapshot);
  useEffect(()=>{
    controller.clearAccount();void controller.refresh();
    const calendar=setInterval(()=>{if(controller.getSnapshot().filters.today!==today())void controller.refresh();},60000);
    return ()=>{clearInterval(calendar);controller.clearAccount();};
  },[controller,accountId]);
  async function save(draft:ApplicationDraft,id?:string,version?:number) {
    return controller.mutate(isCurrent=>applicationApi.save(draft,id,version,isCurrent));
  }
  async function remove(app:Application) {return controller.mutate(isCurrent=>applicationApi.remove(app,isCurrent));}
  async function restoreDeleted(app:Application) {return controller.mutate(isCurrent=>applicationApi.restoreDeleted(app,isCurrent));}
  return {workspace,controller,error:workspace.error,ready:workspace.ready,busy:workspace.busy,
    save,remove,restoreDeleted,refresh:controller.refresh,loadDetail:controller.loadDetail};
}
