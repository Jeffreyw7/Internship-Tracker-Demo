import {useEffect,useState} from 'react';
import {postingApi,type CurrentDescription} from '../data/postingApi';

/** Description text belongs to the open drawer, never the full workspace dataset. */
export function usePostingDescription(id:string|undefined,version:number|undefined) {
  const [attempt,setAttempt]=useState(0);
  const [result,setResult]=useState<{current:CurrentDescription|null;loading:boolean;error:string}>({current:null,loading:!!id,error:''});
  useEffect(()=>{
    if(!id){setResult({current:null,loading:false,error:''});return;}
    const controller=new AbortController();
    setResult({current:null,loading:true,error:''});
    postingApi.current(id,controller.signal).then(current=>{
      if(!controller.signal.aborted)setResult({current,loading:false,error:''});
    }).catch(error=>{if(!controller.signal.aborted)setResult({current:null,loading:false,error:error instanceof Error?error.message:'Could not load the saved description.'});});
    return()=>controller.abort();
  },[id,version,attempt]);
  return {...result,retry:()=>setAttempt(n=>n+1)};
}
