// Displays application details and a separate editable draft inside a native modal dialog.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowUpRight, BriefcaseBusiness, CalendarDays, Check, Circle, MapPin, Pencil, Plus, Trash2, X } from 'lucide-react';
import { blankDraft, formatDate, STATUSES, today, type Application, type ApplicationDraft, type InterviewStage } from '../domain/application';
import { compareApplicationDraft, conflictValue, editableDraft } from '../domain/applicationConflict';
import { ApiError } from '../data/api';
import { CompanyMark, StatusBadge } from './Shared';
// Receive persistence callbacks from the workspace rather than calling the API directly.
interface Props {
  application:Application|null; isNew:boolean; onClose:()=>void;
  onSave:(draft:ApplicationDraft,id?:string,version?:number)=>Promise<void>; onDelete:(app:Application)=>Promise<void>;
  onLoadLatest?:(id:string)=>Promise<Application|null>;
  createPending?:boolean; onCheckCreate?:()=>Promise<void>; onCancelCreate?:()=>Promise<void>;
}
type Conflict={status:'loading'}|{status:'ready';latest:Application}|{status:'missing'}|{status:'failed';message:string};
export function ApplicationDrawer({application,isNew,onClose,onSave,onDelete,onLoadLatest,createPending=false,onCheckCreate,onCancelCreate}:Props) {
  const dialog=useRef<HTMLDialogElement>(null);
  const [editing,setEditing]=useState(isNew);
  // Clone the record so typing does not mutate the displayed application before saving.
  const [draft,setDraft]=useState<ApplicationDraft>(application?editableDraft(application):blankDraft());
  const [error,setError]=useState('');
  const [confirmDelete,setConfirmDelete]=useState(false);
  const [saving,setSaving]=useState(false);
  const draftFrozen=isNew&&createPending;
  const [conflict,setConflict]=useState<Conflict|null>(null),[acknowledged,setAcknowledged]=useState(false);
  // Keep the complete editing base until the user explicitly adopts a newer saved version.
  const base=useRef(application?structuredClone(application):null),writePending=useRef(false),conflictPending=useRef(false);
  const mounted=useRef(false),latestRead=useRef(0);
  const latestProp=useRef(application);latestProp.current=application;
  const deleteConfirmation=useRef<HTMLDivElement>(null);
  const saveError=useRef<HTMLDivElement>(null);
  // Use native dialog focus handling and close it when the component unmounts.
  useEffect(()=>{mounted.current=true;dialog.current?.showModal();return ()=>{mounted.current=false;latestRead.current++;dialog.current?.close();};},[]);
  // Move keyboard focus to confirmation and error messages when they appear.
  useEffect(()=>{if(confirmDelete){deleteConfirmation.current?.scrollIntoView({block:'nearest'});deleteConfirmation.current?.focus();}},[confirmDelete]);
  useEffect(()=>{if(error&&!saving){saveError.current?.scrollIntoView({block:'nearest'});saveError.current?.focus();}},[error,saving]);
  // Update one draft field without replacing unrelated values.
  function update<K extends keyof ApplicationDraft>(key:K,value:ApplicationDraft[K]) {if(writePending.current||draftFrozen)return;setDraft(d=>({...d,[key]:value}));setAcknowledged(false);}
  // Patch an interview stage by its stable local ID.
  function updateStage(id:string,patch:Partial<InterviewStage>) {update('stages',draft.stages.map(s=>s.id===id?{...s,...patch}:s));}
  async function loadLatest() {
    const opened=base.current;if(!opened)return;const read=++latestRead.current;
    const current=()=>mounted.current&&read===latestRead.current;
    conflictPending.current=true;setConflict({status:'loading'});setAcknowledged(false);
    try {
      const latest=onLoadLatest?await onLoadLatest(opened.id):latestProp.current;
      if(!current())return;
      if(!latest||latest.deleted){setConflict({status:'missing'});return;}
      if(latest.id!==opened.id||latest.version<=opened.version)throw new Error('A newer saved version is not available yet. Check again before saving.');
      setConflict({status:'ready',latest:structuredClone(latest)});
    } catch(e) {if(current())setConflict({status:'failed',message:e instanceof Error?e.message:'Could not load the latest saved application.'});}
  }
  const comparison=conflict?.status==='ready'&&base.current?compareApplicationDraft(base.current,draft,conflict.latest):null;
  function resolveConflict(keepChanges:boolean) {
    if(conflict?.status!=='ready'||!comparison||(keepChanges&&comparison.hasOverlaps&&!acknowledged))return;
    setDraft(keepChanges?comparison.merged:editableDraft(conflict.latest));base.current=structuredClone(conflict.latest);
    conflictPending.current=false;setConflict(null);setAcknowledged(false);setError('');
  }
  // The ref closes the same-event gap before React renders the disabled submit button.
  async function submit(e:FormEvent) {
    e.preventDefault();if(writePending.current||conflictPending.current)return;
    writePending.current=true;setSaving(true);setError('');
    try {await onSave(draft,isNew?undefined:base.current?.id,isNew?undefined:base.current?.version);}
    catch(err){if(mounted.current){setError(err instanceof Error?err.message:'Changes could not be saved. Please try again.');
      if(!isNew&&err instanceof ApiError){if(err.status===409)await loadLatest();else if(err.status===404){conflictPending.current=true;setConflict({status:'missing'});}}
    }}
    finally{writePending.current=false;if(mounted.current)setSaving(false);}
  }
  async function recoverCreate(action:(()=>Promise<void>)|undefined) {
    if(!action||writePending.current)return;writePending.current=true;setSaving(true);setError('');
    try {await action();}catch(e){if(mounted.current)setError(e instanceof Error?e.message:'Could not check the pending save.');}
    finally{writePending.current=false;if(mounted.current)setSaving(false);}
  }
  // Request deletion and keep the dialog open if the server rejects it.
  async function deleteRecord(){if(!application||writePending.current)return;writePending.current=true;setSaving(true);setError('');try{await onDelete(application);}catch(e){if(mounted.current)setError(e instanceof Error?e.message:'Could not delete this application.');}finally{writePending.current=false;if(mounted.current)setSaving(false);}}
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
            <label>Company <span className="required">*</span><input autoFocus required readOnly={draftFrozen} maxLength={100} placeholder="e.g. Figma" value={draft.company} onChange={e=>update('company',e.target.value)}/></label>
            <label>Role <span className="required">*</span><input required readOnly={draftFrozen} maxLength={180} placeholder="e.g. Software Engineer Intern" value={draft.role} onChange={e=>update('role',e.target.value)}/></label>
            <div className="form-grid"><label>Location<input readOnly={draftFrozen} maxLength={120} placeholder="New York, NY or Remote" value={draft.location} onChange={e=>update('location',e.target.value)}/></label><label>Status<select disabled={draftFrozen} value={draft.status} onChange={e=>update('status',e.target.value as ApplicationDraft['status'])}>{STATUSES.map(s=><option key={s}>{s}</option>)}</select></label></div>
          </div>
          {/* Capture application, deadline, and follow-up dates without sending reminders. */}
          <div className="form-section"><h3><CalendarDays size={16}/> Dates to remember</h3><div className="form-grid">
            <label>Application date{draft.status!=='Saved'&&<span className="required"> *</span>}<input type="date" readOnly={draftFrozen} required={draft.status!=='Saved'} max={today()} value={draft.appliedDate} onChange={e=>update('appliedDate',e.target.value)}/></label>
            <label>Application deadline<input type="date" readOnly={draftFrozen} value={draft.deadline} onChange={e=>update('deadline',e.target.value)}/></label>
            <label>Follow-up date<input type="date" readOnly={draftFrozen} value={draft.followUpDate} onChange={e=>update('followUpDate',e.target.value)}/></label>
          </div><small className="field-note">Follow-ups appear in your workspace. No notifications are sent.</small></div>
          {/* Manage ordered interview stages, including dates required for scheduled rounds. */}
          <div className="form-section"><div className="section-heading"><h3><Circle size={16}/> Interview stages</h3><button className="text-button" type="button" disabled={draftFrozen||draft.stages.length>=30} onClick={()=>update('stages',[...draft.stages,{id:crypto.randomUUID(),name:'',state:'Planned',date:''}])}><Plus size={15}/> Add stage</button></div>
            {draft.stages.length===0?<p className="form-empty">Add recruiter screens, technical rounds, and final interviews.</p>:draft.stages.map((s,i)=><div className="stage-editor" key={s.id}><div className="stage-number">{i+1}</div><div className="stage-fields"><label className="sr-only" htmlFor={'name-'+s.id}>Stage {i+1} name</label><input id={'name-'+s.id} readOnly={draftFrozen} placeholder="e.g. Technical interview" maxLength={120} required value={s.name} onChange={e=>updateStage(s.id,{name:e.target.value})}/><div className="form-grid"><label className="sr-only" htmlFor={'date-'+s.id}>Stage {i+1} date</label><input id={'date-'+s.id} type="date" readOnly={draftFrozen} required={s.state==='Scheduled'} value={s.date} onChange={e=>updateStage(s.id,{date:e.target.value})}/><label className="sr-only" htmlFor={'state-'+s.id}>Stage {i+1} status</label><select id={'state-'+s.id} disabled={draftFrozen} value={s.state} onChange={e=>updateStage(s.id,{state:e.target.value as InterviewStage['state']})}><option>Planned</option><option>Scheduled</option><option>Completed</option></select></div></div><button className="icon-button remove-stage" type="button" disabled={draftFrozen} aria-label={`Remove stage ${i+1}`} onClick={()=>update('stages',draft.stages.filter(x=>x.id!==s.id))}><X size={15}/></button></div>)}
          </div>
          {/* Keep free-form preparation notes within the server-supported length limit. */}
          <div className="form-section"><h3><Pencil size={16}/> Notes</h3><label className="sr-only" htmlFor="notes">Notes</label><textarea id="notes" readOnly={draftFrozen} rows={5} maxLength={10000} value={draft.notes} placeholder="Contacts, prep notes, things to ask, or anything worth remembering…" onChange={e=>update('notes',e.target.value)}/></div>
          {conflict&&<section className="form-section" aria-labelledby="conflict-title">
            <h3 id="conflict-title">Review the latest saved application</h3>
            <p>Your unsaved changes are still in the form. Saving is paused until you choose how to continue.</p>
            {conflict.status==='loading'?<p role="status">Loading the latest saved values…</p>:conflict.status==='ready'&&comparison?<>
              <p className="muted">Compare the changed fields below. Reapplying keeps your edits and preserves saved changes to fields you did not edit.</p>
              {comparison.changes.map(change=><div className="form-section" key={change.field}>
                <h3>{change.label}{change.overlap?' — changed in both versions':''}</h3>
                <dl><dt>When you started editing</dt><dd className="detail-notes">{conflictValue(base.current![change.field])}</dd>
                  <dt>Your draft</dt><dd className="detail-notes">{conflictValue(draft[change.field])}</dd>
                  <dt>Latest saved value</dt><dd className="detail-notes">{conflictValue(conflict.latest[change.field])}</dd></dl>
              </div>)}
              {comparison.hasOverlaps&&<label><input type="checkbox" checked={acknowledged} onChange={e=>setAcknowledged(e.target.checked)}/> I reviewed the overlapping changes and want to replace those saved values with my draft.</label>}
              <div className="form-grid"><button type="button" className="button secondary" onClick={()=>resolveConflict(false)}>Use latest saved values</button>
                <button type="button" className="button primary" disabled={comparison.hasOverlaps&&!acknowledged} onClick={()=>resolveConflict(true)}>Reapply my changes</button></div>
              <p className="field-note">Neither choice saves automatically. Review the form, then select Save changes.</p>
            </>:<>
              <p role="status">{conflict.status==='missing'?'This application was deleted or is no longer available. Your draft is preserved here so you can copy your notes.':conflict.status==='failed'?conflict.message:'Check the latest saved version before continuing.'}</p>
              <button type="button" className="button secondary" onClick={()=>void loadLatest()}>Check latest saved version</button>
            </>}
          </section>}
        </div>{error&&<div ref={saveError} tabIndex={-1} className="error-box" role="alert">{error}</div>}
        {isNew&&createPending&&<div className="error-box" role="status">The save outcome is not confirmed yet. Your submitted values are frozen. Check status, retry the same save, or cancel the pending save.</div>}
        <footer className="drawer-footer">{isNew&&createPending?<>
          <button type="button" className="button secondary" disabled={saving||!onCheckCreate} onClick={()=>void recoverCreate(onCheckCreate)}>Check save status</button>
          <button type="submit" className="button primary" disabled={saving}>Retry save</button>
          <button type="button" className="text-button danger-text" disabled={saving||!onCancelCreate} onClick={()=>void recoverCreate(onCancelCreate)}>Cancel pending save</button>
        </>:<><button type="button" className="button secondary" disabled={saving} onClick={()=>{
          if(isNew||!application||application.deleted||conflict?.status==='missing')onClose();
          else{latestRead.current++;setEditing(false);setDraft(editableDraft(application));base.current=structuredClone(application);conflictPending.current=false;setConflict(null);setError('');}
        }}>Cancel</button><button className="button primary" disabled={saving||!!conflict} type="submit"><Check size={16}/>{saving?'Saving…':isNew?'Add application':'Save changes'}</button></>}</footer>
      </form>:a&&<><div className="drawer-body" inert={saving}><div className="detail-intro"><CompanyMark company={a.company} large/><StatusBadge status={a.status}/></div><h2 id="drawer-title">{a.company}</h2><p className="detail-role">{a.role}</p><p className="location"><MapPin size={14}/>{a.location||'Location not set'}</p>
        <div className="detail-dates"><div><span>Applied</span><strong>{formatDate(a.appliedDate)}</strong></div><div><span>Deadline</span><strong>{formatDate(a.deadline)}</strong></div><div><span>Follow-up</span><strong>{formatDate(a.followUpDate)}</strong></div></div>
        <div className="form-section"><div className="section-heading"><h3>Interview journey</h3><span className="mini-count">{a.stages.filter(s=>s.state==='Completed').length} / {a.stages.length} complete</span></div>
          {a.stages.length?<div className="timeline">{a.stages.map((s,i)=><div className={'timeline-stage '+s.state.toLowerCase()} key={s.id}><div className="timeline-marker">{s.state==='Completed'?<Check size={13}/>:i+1}</div><div><strong>{s.name}</strong><p>{s.date?formatDate(s.date)+' · ':''}{s.state}</p></div></div>)}</div>:<p className="form-empty">Your interview journey starts here. Add stages when you hear back.</p>}
        </div><div className="form-section"><h3>Notes & preparation</h3><p className="detail-notes">{a.notes||'No notes yet. Add contacts, questions, and interview prep here.'}</p></div>
        {error&&<div ref={saveError} tabIndex={-1} className="error-box" role="alert">{error}</div>}<p className="updated">Last updated {new Date(a.updatedAt).toLocaleDateString('en-US',{month:'long',day:'numeric',year:'numeric'})}</p>
        {/* Require an explicit confirmation before requesting a soft deletion. */}
        {confirmDelete&&<div ref={deleteConfirmation} tabIndex={-1} className="delete-confirm"><strong>Delete this application?</strong><p>You can undo this immediately after deleting.</p><div><button className="button secondary" onClick={()=>setConfirmDelete(false)}>Keep application</button><button className="button danger" disabled={saving} onClick={deleteRecord}>Delete application</button></div></div>}
      </div><footer className="drawer-footer"><button className="text-button danger-text" disabled={saving} onClick={()=>setConfirmDelete(true)}><Trash2 size={16}/> Delete</button><button className="button primary" disabled={saving} onClick={()=>{latestRead.current++;setDraft(editableDraft(a));base.current=structuredClone(a);conflictPending.current=false;setConflict(null);setEditing(true);setError('');}}><Pencil size={15}/> Edit application <ArrowUpRight size={15}/></button></footer></>}
    </div>
  </dialog>;
}
