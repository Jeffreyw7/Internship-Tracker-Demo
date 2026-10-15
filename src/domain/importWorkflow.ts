import type {ImportOptions,ImportPreview,ImportReceipt,ImportStatus,RowOverride} from './applicationImport.ts';
interface Dependencies {guard:()=>()=>boolean;preview:(file:File,options:ImportOptions,current:()=>boolean)=>Promise<ImportPreview>;status:(id:string,current:()=>boolean)=>Promise<ImportStatus>;terminal?:(error:unknown)=>'MISSING'|'EXPIRED'|null}
interface Snapshot {file:File|null;options:ImportOptions;preview:ImportPreview|null;revision:number;pending:boolean;error:string;receipt:ImportReceipt|null;uncertainPredecessor:string|null;current:boolean;retiredReference:string|null;requiresDuplicateAcknowledgement:boolean}
const initialOptions=():ImportOptions=>({schemaVersion:1,mapping:{},dateFormat:'ISO',rows:[]});
// Own revision/lifetime ordering outside React so lost responses and stale-account races are testable.
export function createImportWorkflow(deps:Dependencies) {
  let snapshot:Snapshot={file:null,options:initialOptions(),preview:null,revision:0,pending:false,error:'',receipt:null,uncertainPredecessor:null,current:false,retiredReference:null,requiresDuplicateAcknowledgement:false};
  let active=false,lifetime=0,requesting=false,uncertainRevision=-1,uncertainBaselineCurrent=false;
  const listeners=new Set<()=>void>();
  const publish=(patch:Partial<Snapshot>)=>{snapshot={...snapshot,...patch};listeners.forEach(listener=>listener());};
  const capture=(requireRevision=true)=>{const life=lifetime,revision=snapshot.revision,account=deps.guard();return ()=>active&&life===lifetime&&(!requireRevision||revision===snapshot.revision)&&account();};
  function edit(change:(value:ImportOptions)=>ImportOptions){publish({revision:snapshot.revision+1,options:change(snapshot.options),current:false,error:''});}
  function chooseFile(file:File|null){publish({file,revision:snapshot.revision+1,options:initialOptions(),preview:null,current:false,error:'',receipt:null,uncertainPredecessor:null});}
  function updateRow(rowNumber:number,change:Partial<RowOverride>){edit(options=>{const old=options.rows.find(r=>r.rowNumber===rowNumber)??{rowNumber};return {...options,rows:[...options.rows.filter(r=>r.rowNumber!==rowNumber),{...old,...change}]};});}
  function restartReview(acknowledgeDuplicateRisk=false){if(snapshot.requiresDuplicateAcknowledgement&&!acknowledgeDuplicateRisk)return;publish({preview:null,current:false,uncertainPredecessor:null,retiredReference:null,requiresDuplicateAcknowledgement:false,revision:snapshot.revision+1,error:''});}
  async function createPreview(){
    if(!snapshot.file||requesting||snapshot.uncertainPredecessor||snapshot.retiredReference||!active)return;
    const predecessor=snapshot.preview?.id??null,baselineCurrent=snapshot.current;
    requesting=true;publish({pending:true,current:false,revision:snapshot.revision+1,error:''});
    const current=capture(),alive=capture(false),revision=snapshot.revision;
    try {const result=await deps.preview(snapshot.file,{...snapshot.options,supersedesPreviewId:predecessor},current);if(current())publish({preview:result,current:true});}
    catch(e){if(current()){uncertainRevision=revision;uncertainBaselineCurrent=baselineCurrent;publish({error:e instanceof Error?e.message:'Could not create preview.',uncertainPredecessor:predecessor});}}
    finally{if(alive()){requesting=false;publish({pending:false});}}
  }
  async function recoverPreview(){
    if(!snapshot.uncertainPredecessor||requesting||!active)return;
    const predecessor=snapshot.uncertainPredecessor,current=capture(),alive=capture(false);requesting=true;publish({pending:true});
    try {
      let id=predecessor;
      for(let hops=0;hops<10;hops++){
        const status=await deps.status(id,current);if(!current())return;
        if(status.status==='SUPERSEDED'){id=status.successorId;continue;}
        if(status.status==='COMPLETED'){publish({receipt:status.receipt,uncertainPredecessor:null,error:'This import already completed. Refresh your workspace.'});return;}
        if(status.status==='PREVIEW'){
          publish({preview:status.preview,current:snapshot.revision===uncertainRevision&&(id!==predecessor||uncertainBaselineCurrent)});
          publish({uncertainPredecessor:null,error:id===predecessor?'The prior preview is unchanged. You can explicitly create the corrected preview again.':''});return;
        }
        publish({error:'Preview outcome remains unresolved. Check status again.'});return;
      }
      publish({error:'Check status again to follow the preview.'});
    }catch(e){if(current()){
      const terminal=deps.terminal?.(e);
      if(terminal)publish({retiredReference:predecessor,requiresDuplicateAcknowledgement:terminal==='MISSING',uncertainPredecessor:null,current:false,error:terminal==='MISSING'?'This import is no longer available. A previous completion receipt may have expired; a fresh import could duplicate earlier changes.':'The prior preview expired. Start a fresh review before importing.'});
      else publish({error:e instanceof Error?e.message:'Could not check preview status.'});
    }}
    finally{if(alive()){requesting=false;publish({pending:false});}}
  }
  return {getSnapshot:()=>snapshot,subscribe:(listener:()=>void)=>{listeners.add(listener);return()=>{listeners.delete(listener);};},activate(){active=true;lifetime++;requesting=false;if(snapshot.pending)publish({pending:false});},dispose(){active=false;lifetime++;},edit,chooseFile,updateRow,createPreview,recoverPreview,restartReview};
}
