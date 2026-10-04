import {useEffect,useState} from 'react';
import {postingApi,type CurrentDescription} from '../data/postingApi';

/** Description text belongs to the open drawer, never the full workspace dataset. */
export function usePostingDescription(id:string|undefined,version:number|undefined,refresh:()=>Promise<void>) {
  const [attempt,setAttempt]=useState(0);
  const [result,setResult]=useState<{id:string|undefined;version:number|undefined;current:CurrentDescription|null;loading:boolean;error:string}>({id,version,current:null,loading:!!id,error:''});
  useEffect(()=>{
    const pending={id,version,current:null,loading:!!id,error:''};
    setResult(pending);
    if(!id)return;
    const controller=new AbortController();
    postingApi.current(id,controller.signal).then(async current=>{
      if(controller.signal.aborted)return;
      if(current.applicationVersion!==version) {
        // Refresh the shared record before pairing its contacts/pay with this description.
        setResult({...pending,loading:false,error:'This application changed. Refreshing saved details. Retry if the description remains unavailable.'});
        await refresh();
        return;
      }
      setResult({...pending,current,loading:false});
    }).catch(error=>{if(!controller.signal.aborted)setResult({...pending,loading:false,error:error instanceof Error?error.message:'Could not load the saved description.'});});
    return()=>controller.abort();
  },[id,version,attempt,refresh]);
  // A prop change renders before effect cleanup; never expose the previous record in that frame.
  const visible=result.id===id&&result.version===version?result:{current:null,loading:!!id,error:''};
  return {...visible,retry:()=>setAttempt(n=>n+1)};
}
