import {useEffect,useRef,useState} from 'react';
import {compensationLabel,type PostingDetails} from '../domain/postingDetails';
import {postingApi,type CurrentDescription,type DescriptionHistory,type DescriptionSnapshot} from '../data/postingApi';
import {downloadJson} from '../domain/applicationExport';
interface Props {applicationId:string;value:PostingDetails;current:CurrentDescription|null;loading:boolean;error:string;onRetry:()=>void}
const stamp=(value:string)=>new Date(value).toLocaleString();
function ExternalLink({url,children}:{url:string;children:React.ReactNode}) {return url?<a className="posting-link" href={url} target="_blank" rel="noopener noreferrer">{children} ↗</a>:null;}
export function PostingDetailsPanel({applicationId,value:p,current,loading,error,onRetry}:Props) {
  const [history,setHistory]=useState<DescriptionHistory|null>(null);
  const [selected,setSelected]=useState<DescriptionSnapshot|null>(null);
  const [historyOpen,setHistoryOpen]=useState(false);
  const [operationError,setOperationError]=useState('');
  const [busy,setBusy]=useState(false);
  const generation=useRef(0);
  useEffect(()=>()=>{generation.current++;},[applicationId]);
  async function operation(action:()=>Promise<void>) {
    const active=++generation.current;setBusy(true);setOperationError('');
    try {await action();}catch(e){if(active===generation.current)setOperationError(e instanceof Error?e.message:'Could not load posting history.');}
    finally{if(active===generation.current)setBusy(false);}
  }
  async function loadHistory(page=0) {
    await operation(async()=>{const active=generation.current;const result=await postingApi.history(applicationId,page);if(active===generation.current){setHistory(result);setHistoryOpen(true);setSelected(null);}});
  }
  async function showSnapshot(id:string) {
    await operation(async()=>{const active=generation.current;const result=await postingApi.snapshot(applicationId,id);if(active===generation.current)setSelected(result);});
  }
  async function archive() {
    await operation(async()=>{const active=generation.current;const result=await postingApi.archive(applicationId);if(active===generation.current)downloadJson(result,`campus-posting-${applicationId}.json`);});
  }
  const snapshot=current?.snapshot;
  const referral=[p.referralChannel==='UNKNOWN'?'':p.referralChannel.toLowerCase().replaceAll('_',' '),p.referralSource,p.referrerName,p.referrerContact].filter(Boolean).join(' · ');
  return <>
    <div className="form-section posting-details"><h3>Posting & contacts</h3>
      <ExternalLink url={p.postingUrl}>Open original posting</ExternalLink>
      <dl><dt>Recruiter</dt><dd>{[p.recruiterName,p.recruiterRole].filter(Boolean).join(' · ')||'Not provided'}</dd>
        {p.recruiterEmail&&<><dt>Email</dt><dd>{p.recruiterEmail}</dd></>}{p.recruiterPhone&&<><dt>Phone</dt><dd>{p.recruiterPhone}</dd></>}
        <dt>Referral</dt><dd>{referral||'Not provided'}</dd><dt>Compensation</dt><dd>{compensationLabel(p)}</dd></dl>
      <ExternalLink url={p.recruiterProfileUrl}>Recruiter profile</ExternalLink>
      {p.compensationOriginalText&&<p className="detail-notes">{p.compensationOriginalText}</p>}
    </div>
    <div className="form-section"><h3>Saved description</h3>
      {loading?<p className="form-empty" role="status">Loading saved description…</p>:error?<div className="posting-message" role="alert">{error} <button className="text-button" onClick={onRetry}>Retry description</button></div>:
        snapshot?<><p className="posting-capture">Captured {stamp(snapshot.capturedAt)} · version {snapshot.revision}</p><p className="detail-notes posting-description">{snapshot.descriptionText||'The current description was cleared. Earlier versions remain in history.'}</p></>:<p className="form-empty">No description saved yet. Paste the posting when you edit this application.</p>}
      <div className="posting-actions"><button className="text-button" disabled={busy} onClick={()=>historyOpen?setHistoryOpen(false):void loadHistory()}>Description history</button><button className="text-button" disabled={busy} onClick={()=>void archive()}>{busy?'Loading…':'Download description archive'}</button></div>
      {operationError&&<p className="error-box" role="alert">{operationError}</p>}
      {historyOpen&&history&&<div className="posting-history"><p className="field-note">Earlier descriptions remain available when the current description is cleared.</p>
        {history.content.length?history.content.map(s=><button key={s.id} className="posting-history-item" disabled={busy} onClick={()=>void showSnapshot(s.id)}>Version {s.revision} · {stamp(s.capturedAt)}</button>):<p className="form-empty">No captured versions yet.</p>}
        {history.totalPages>1&&<div className="posting-actions"><button className="text-button" disabled={busy||history.page===0} onClick={()=>void loadHistory(history.page-1)}>Newer versions</button><span>Page {history.page+1} of {history.totalPages}</span><button className="text-button" disabled={busy||history.page+1>=history.totalPages} onClick={()=>void loadHistory(history.page+1)}>Older versions</button></div>}
        {selected&&<div className="posting-history-preview"><p className="posting-capture">Version {selected.revision} · captured {stamp(selected.capturedAt)}</p><ExternalLink url={selected.sourceUrl}>Source posting</ExternalLink><p className="detail-notes posting-description">{selected.descriptionText||'Empty description'}</p></div>}
      </div>}
    </div>
  </>;
}
