import type {ExportJob} from '../data/exportApi.ts';

interface ExportPort {
  create:(current:()=>boolean)=>Promise<ExportJob>;
  status:(id:string,current:()=>boolean)=>Promise<ExportJob>;
  cancel:(id:string,current:()=>boolean)=>Promise<ExportJob>;
  download:(id:string,current:()=>boolean)=>Promise<Blob>;
}
export interface ExportSnapshot {job:ExportJob|null;busy:boolean;downloading:boolean;error:string;message:string}
export function createExportController(api:ExportPort,options:{schedule?:(callback:()=>void,delay:number)=>unknown;cancel?:(id:unknown)=>void}={}) {
  const schedule=options.schedule??((callback,delay)=>setTimeout(callback,delay));
  const cancelTimer=options.cancel??(id=>clearTimeout(id as ReturnType<typeof setTimeout>));
  const listeners=new Set<()=>void>();let generation=0,readEpoch=0,visible=true,timer:unknown;
  let snapshot:ExportSnapshot={job:null,busy:false,downloading:false,error:'',message:''};
  const active=(job:ExportJob|null)=>job?.state==='PENDING'||job?.state==='RUNNING';
  const message=(job:ExportJob|null)=>!job?'':active(job)?'Preparing CSV for all your applications…':job.state==='READY'?'Your full application CSV is ready.':job.state==='EXPIRED'?'This export expired. Create a new export.':job.state==='CANCELLED'?'Export cancelled.':job.failureCode==='SIZE_LIMIT'?'This export exceeds the size limit.':'Export failed. Create a new export or try again later.';
  function publish(patch:Partial<ExportSnapshot>) {snapshot={...snapshot,...patch};snapshot={...snapshot,message:message(snapshot.job)};for(const listener of listeners)listener();}
  function stop() {if(timer!==undefined){cancelTimer(timer);timer=undefined;}}
  function poll() {stop();if(visible&&active(snapshot.job)&&!snapshot.busy&&!snapshot.error)timer=schedule(refresh,2000);}
  const errorMessage=(error:unknown)=>error instanceof Error?error.message:'Could not prepare your CSV. Try again.';
  async function start() {
    if(snapshot.busy||active(snapshot.job))return;
    stop();readEpoch++;const account=generation;const current=()=>account===generation;
    publish({busy:true,error:'',job:null});
    try {const job=await api.create(current);if(current())publish({job});}
    catch(error){if(current())publish({error:errorMessage(error)});}
    finally{if(current()){publish({busy:false});poll();}}
  }
  async function refresh() {
    stop();const job=snapshot.job;if(!job||snapshot.busy||!active(job)||!visible)return;
    const account=generation,epoch=++readEpoch;const current=()=>account===generation&&epoch===readEpoch;
    try{const next=await api.status(job.id,current);if(current())publish({job:next,error:''});}
    catch(error){if(current())publish({error:errorMessage(error)});}
    finally{if(current())poll();}
  }
  async function cancel() {
    const job=snapshot.job;if(!job||snapshot.busy||!active(job))return;
    stop();readEpoch++;const account=generation;const current=()=>account===generation;
    publish({busy:true,error:''});
    try{const next=await api.cancel(job.id,current);if(current())publish({job:next});}
    catch(error){if(current())publish({error:errorMessage(error)});}
    finally{if(current()){publish({busy:false});poll();}}
  }
  async function download():Promise<Blob|null> {
    const job=snapshot.job;if(!job||job.state!=='READY'||snapshot.downloading)return null;
    const account=generation;const current=()=>account===generation&&snapshot.job?.id===job.id&&snapshot.job.state==='READY';
    publish({downloading:true,error:''});
    try{const blob=await api.download(job.id,current);return current()?blob:null;}
    catch(error){if(current())publish({error:errorMessage(error),...((error as {status?:number})?.status===410?{job:{...job,state:'EXPIRED' as const,downloadPath:null}}:{})});return null;}
    finally{if(account===generation)publish({downloading:false});}
  }
  function clear() {generation++;readEpoch++;stop();publish({job:null,busy:false,downloading:false,error:''});}
  return {getSnapshot:()=>snapshot,subscribe:(listener:()=>void)=>{listeners.add(listener);return()=>{listeners.delete(listener);};},start,refresh,cancel,download,clear,
    setVisible(value:boolean){visible=value;if(value){if(active(snapshot.job))void refresh();}else stop();}};
}
