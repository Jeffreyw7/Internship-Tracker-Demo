// Displays application details and a separate editable draft inside a native modal dialog.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowUpRight, BriefcaseBusiness, CalendarDays, Check, Circle, MapPin, Pencil, Plus, Trash2, X } from 'lucide-react';
import { blankDraft, formatDate, STATUSES, today, type Application, type ApplicationDraft, type InterviewStage } from '../domain/application';
import { CompanyMark, StatusBadge } from './Shared';
import {PostingFields} from './PostingFields';
import {PostingDetailsPanel} from './PostingDetailsPanel';
import {blankPostingDetails} from '../domain/postingDetails';
import {usePostingDescription} from '../hooks/usePostingDescription';
import {postingApi} from '../data/postingApi';
import {applicationApi} from '../data/applicationApi';
import {ApiError} from '../data/api';
// Receive persistence callbacks from the workspace rather than calling the API directly.
interface Props { application:Application|null; isNew:boolean; onClose:()=>void; onSave:(draft:ApplicationDraft,id?:string,version?:number)=>Promise<void>; onDelete:(app:Application)=>Promise<void>; onRefresh:()=>Promise<void> }
export function ApplicationDrawer({application,isNew,onClose,onSave,onDelete,onRefresh}:Props) {
  const dialog=useRef<HTMLDialogElement>(null);
  const [editing,setEditing]=useState(isNew);
  // Clone the record so typing does not mutate the displayed application before saving.
  const [draft,setDraft]=useState<ApplicationDraft>(application?structuredClone(application):blankDraft());
  const [error,setError]=useState('');
  const [confirmDelete,setConfirmDelete]=useState(false);
  const [saving,setSaving]=useState(false);
  const description=usePostingDescription(application?.id,application?.version,onRefresh);
  const [descriptionBase,setDescriptionBase]=useState('');
  const [descriptionAvailable,setDescriptionAvailable]=useState(isNew);
  const [descriptionError,setDescriptionError]=useState('');
  const [conflict,setConflict]=useState(false);
  const editorGeneration=useRef(0);
  useEffect(()=>()=>{editorGeneration.current++;},[]);
  // Remember the version opened for editing to detect concurrent changes.
  const originalVersion=useRef(application?.version);
  const deleteConfirmation=useRef<HTMLDivElement>(null);
  const saveError=useRef<HTMLDivElement>(null);
  // Use native dialog focus handling and close it when the component unmounts.
  useEffect(()=>{dialog.current?.showModal(); return ()=>dialog.current?.close();},[]);
  // Move keyboard focus to confirmation and error messages when they appear.
  useEffect(()=>{if(confirmDelete){deleteConfirmation.current?.scrollIntoView({block:'nearest'});deleteConfirmation.current?.focus();}},[confirmDelete]);
  useEffect(()=>{if(error){saveError.current?.scrollIntoView({block:'nearest'});saveError.current?.focus();}},[error]);
  // Update one draft field without replacing unrelated values.
  function update<K extends keyof ApplicationDraft>(key:K,value:ApplicationDraft[K]) {setDraft(d=>({...d,[key]:value}));}
  // Patch an interview stage by its stable local ID.
  function updateStage(id:string,patch:Partial<InterviewStage>) {update('stages',draft.stages.map(s=>s.id===id?{...s,...patch}:s));}
  // Prevent duplicate saves and pass the editor\'s original version to the parent.
  async function submit(e:FormEvent) {e.preventDefault();if(saving)return;setSaving(true);setError('');try {await onSave(draft,application?.id,originalVersion.current);}catch(err){setConflict(err instanceof ApiError&&err.status===409);setError(err instanceof Error?err.message:'Changes could not be saved. Please try again.');}finally{setSaving(false);}}
  async function loadEditorDescription(active=++editorGeneration.current) {
    if(!application)return;
    try {
      const current=await postingApi.current(application.id);
      if(active!==editorGeneration.current)return;
      if(current.applicationVersion!==originalVersion.current) {setConflict(true);throw new Error('This application changed. Reload the saved version before editing its description.');}
      setDescriptionBase(current.snapshot?.descriptionText??'');setDescriptionAvailable(true);setDescriptionError('');
    }catch(e){if(active===editorGeneration.current){setDescriptionAvailable(false);setDescriptionError(e instanceof Error?e.message:'Could not load description.');}}
  }
  async function beginEditing() {
    if(!application||saving)return;
    const active=++editorGeneration.current;
    setSaving(true);setError('');setConflict(false);
    try {
      const latest=await applicationApi.get(application.id);
      if(active!==editorGeneration.current)return;
      setDraft({...structuredClone(latest),postingDetails:structuredClone(latest.postingDetails??blankPostingDetails())});
      originalVersion.current=latest.version;setDescriptionAvailable(false);setDescriptionBase('');setEditing(true);
      await loadEditorDescription(active);
    }catch(e){if(active===editorGeneration.current)setError(e instanceof Error?e.message:'Could not load application.');}
    finally{if(active===editorGeneration.current)setSaving(false);}
  }
  // Request deletion and keep the dialog open if the server rejects it.
  async function deleteRecord(){if(!application||saving)return;setSaving(true);setError('');try{await onDelete(application);}catch(e){setError(e instanceof Error?e.message:'Could not delete this application.');}finally{setSaving(false);}}
  const a=application;
  // Render the detail or edit state, blocking dismissal while a write is in progress.
  return <dialog ref={dialog} className="drawer" aria-labelledby="drawer-title" onCancel={e=>{e.preventDefault();if(!saving)onClose();}} onClick={e=>{if(!saving&&e.target===e.currentTarget)onClose();}}>
    <div className="drawer-shell">
      <header className="drawer-header"><span>{isNew?'NEW OPPORTUNITY':editing?'EDIT APPLICATION':'APPLICATION DETAILS'}</span><button className="icon-button" aria-label="Close application" disabled={saving} onClick={onClose}><X size={20}/></button></header>
      {/* Switch between the editable form and the saved record details. */}
      {editing?<form onSubmit={submit} className="application-form">
        <div className="drawer-body" inert={saving}><h2 id="drawer-title">{isNew?'Make your next move.':'A little progress, updated.'}</h2><p className="muted">{isNew?'Start with the basics. You can add the details as you go.':'Keep every detail of this opportunity in one place.'}</p>
          {/* Collect the core company, role, location, and pipeline status fields. */}
          <div className="form-section"><h3><BriefcaseBusiness size={16}/> The opportunity</h3>
            <label>Company <span className="required">*</span><input autoFocus required maxLength={100} placeholder="e.g. Figma" value={draft.company} onChange={e=>update('company',e.target.value)}/></label>
            <label>Role <span className="required">*</span><input required maxLength={180} placeholder="e.g. Software Engineer Intern" value={draft.role} onChange={e=>update('role',e.target.value)}/></label>
            <div className="form-grid"><label>Location<input maxLength={120} placeholder="New York, NY or Remote" value={draft.location} onChange={e=>update('location',e.target.value)}/></label><label>Status<select value={draft.status} onChange={e=>update('status',e.target.value as ApplicationDraft['status'])}>{STATUSES.map(s=><option key={s}>{s}</option>)}</select></label></div>
          </div>
          <PostingFields value={draft.postingDetails??blankPostingDetails()} onChange={value=>{
            if(value.descriptionSnapshot===descriptionBase)delete value.descriptionSnapshot;
            update('postingDetails',value);
          }} descriptionValue={draft.postingDetails?.descriptionSnapshot??descriptionBase} descriptionAvailable={descriptionAvailable} descriptionError={descriptionError} onRetry={()=>void loadEditorDescription()}/>
          {/* Capture application, deadline, and follow-up dates without sending reminders. */}
          <div className="form-section"><h3><CalendarDays size={16}/> Dates to remember</h3><div className="form-grid">
            <label>Application date{draft.status!=='Saved'&&<span className="required"> *</span>}<input type="date" required={draft.status!=='Saved'} max={today()} value={draft.appliedDate} onChange={e=>update('appliedDate',e.target.value)}/></label>
            <label>Application deadline<input type="date" value={draft.deadline} onChange={e=>update('deadline',e.target.value)}/></label>
            <label>Follow-up date<input type="date" value={draft.followUpDate} onChange={e=>update('followUpDate',e.target.value)}/></label>
          </div><small className="field-note">Follow-ups appear in your workspace. No notifications are sent.</small></div>
          {/* Manage ordered interview stages, including dates required for scheduled rounds. */}
          <div className="form-section"><div className="section-heading"><h3><Circle size={16}/> Interview stages</h3><button className="text-button" type="button" disabled={draft.stages.length>=30} onClick={()=>update('stages',[...draft.stages,{id:crypto.randomUUID(),name:'',state:'Planned',date:''}])}><Plus size={15}/> Add stage</button></div>
            {draft.stages.length===0?<p className="form-empty">Add recruiter screens, technical rounds, and final interviews.</p>:draft.stages.map((s,i)=><div className="stage-editor" key={s.id}><div className="stage-number">{i+1}</div><div className="stage-fields"><label className="sr-only" htmlFor={'name-'+s.id}>Stage {i+1} name</label><input id={'name-'+s.id} placeholder="e.g. Technical interview" maxLength={120} required value={s.name} onChange={e=>updateStage(s.id,{name:e.target.value})}/><div className="form-grid"><label className="sr-only" htmlFor={'date-'+s.id}>Stage {i+1} date</label><input id={'date-'+s.id} type="date" required={s.state==='Scheduled'} value={s.date} onChange={e=>updateStage(s.id,{date:e.target.value})}/><label className="sr-only" htmlFor={'state-'+s.id}>Stage {i+1} status</label><select id={'state-'+s.id} value={s.state} onChange={e=>updateStage(s.id,{state:e.target.value as InterviewStage['state']})}><option>Planned</option><option>Scheduled</option><option>Completed</option></select></div></div><button className="icon-button remove-stage" type="button" aria-label={`Remove stage ${i+1}`} onClick={()=>update('stages',draft.stages.filter(x=>x.id!==s.id))}><X size={15}/></button></div>)}
          </div>
          {/* Keep free-form preparation notes within the server-supported length limit. */}
          <div className="form-section"><h3><Pencil size={16}/> Notes</h3><label className="sr-only" htmlFor="notes">Notes</label><textarea id="notes" rows={5} maxLength={10000} value={draft.notes} placeholder="Contacts, prep notes, things to ask, or anything worth remembering…" onChange={e=>update('notes',e.target.value)}/></div>
          {error&&<div ref={saveError} tabIndex={-1} className="error-box" role="alert">{error}</div>}
          {conflict&&<button type="button" className="button secondary" disabled={saving} onClick={()=>void beginEditing()}>Reload saved version (discard draft)</button>}
        </div><footer className="drawer-footer"><button type="button" className="button secondary" disabled={saving} onClick={()=>{if(isNew)onClose();else{setEditing(false);setDraft(structuredClone(application!));originalVersion.current=application!.version;setError('');}}}>Cancel</button><button className="button primary" disabled={saving} type="submit"><Check size={16}/>{saving?'Saving…':isNew?'Add application':'Save changes'}</button></footer>
      </form>:a&&<><div className="drawer-body" inert={saving}><div className="detail-intro"><CompanyMark company={a.company} large/><StatusBadge status={a.status}/></div><h2 id="drawer-title">{a.company}</h2><p className="detail-role">{a.role}</p><p className="location"><MapPin size={14}/>{a.location||'Location not set'}</p>
        <PostingDetailsPanel applicationId={a.id} value={a.postingDetails??blankPostingDetails()} current={description.current} loading={description.loading} error={description.error} onRetry={description.retry}/>
        <div className="detail-dates"><div><span>Applied</span><strong>{formatDate(a.appliedDate)}</strong></div><div><span>Deadline</span><strong>{formatDate(a.deadline)}</strong></div><div><span>Follow-up</span><strong>{formatDate(a.followUpDate)}</strong></div></div>
        <div className="form-section"><div className="section-heading"><h3>Interview journey</h3><span className="mini-count">{a.stages.filter(s=>s.state==='Completed').length} / {a.stages.length} complete</span></div>
          {a.stages.length?<div className="timeline">{a.stages.map((s,i)=><div className={'timeline-stage '+s.state.toLowerCase()} key={s.id}><div className="timeline-marker">{s.state==='Completed'?<Check size={13}/>:i+1}</div><div><strong>{s.name}</strong><p>{s.date?formatDate(s.date)+' · ':''}{s.state}</p></div></div>)}</div>:<p className="form-empty">Your interview journey starts here. Add stages when you hear back.</p>}
        </div><div className="form-section"><h3>Notes & preparation</h3><p className="detail-notes">{a.notes||'No notes yet. Add contacts, questions, and interview prep here.'}</p></div>
        {error&&<div ref={saveError} tabIndex={-1} className="error-box" role="alert">{error}</div>}<p className="updated">Last updated {new Date(a.updatedAt).toLocaleDateString('en-US',{month:'long',day:'numeric',year:'numeric'})}</p>
        {/* Require an explicit confirmation before requesting a soft deletion. */}
        {confirmDelete&&<div ref={deleteConfirmation} tabIndex={-1} className="delete-confirm"><strong>Delete this application?</strong><p>You can undo this immediately after deleting.</p><div><button className="button secondary" onClick={()=>setConfirmDelete(false)}>Keep application</button><button className="button danger" disabled={saving} onClick={deleteRecord}>Delete application</button></div></div>}
      </div><footer className="drawer-footer"><button className="text-button danger-text" disabled={saving} onClick={()=>setConfirmDelete(true)}><Trash2 size={16}/> Delete</button><button className="button primary" disabled={saving} onClick={()=>void beginEditing()}><Pencil size={15}/> Edit application <ArrowUpRight size={15}/></button></footer></>}
    </div>
  </dialog>;
}
