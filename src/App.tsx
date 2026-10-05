// Coordinates the signed-in workspace: views, filters, editor actions, CSV export, and undo notifications.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { ArrowDown, ArrowDownToLine, ArrowRight, ArrowUpRight, Bell, BriefcaseBusiness, CalendarDays, Check, ChevronDown, ChevronLeft, ChevronRight, CircleCheck, CircleDot, Clock3, GraduationCap, LayoutGrid, LockKeyhole, Menu, Plus, Search, SlidersHorizontal, Sparkles, Target, Trophy, X } from 'lucide-react';
import { dayDistance, formatDate, isClosed, relativeDate, STATUSES, today, type Application, type ApplicationDraft, type Status } from './domain/application';
import { useApplications } from './hooks/useApplications';
import { ApplicationDrawer } from './components/ApplicationDrawer';
import { CompanyButton, CompanyMark, StatusBadge } from './components/Shared';
import type {Tab} from './domain/selectors';
import {completeWorkspaceActivity} from './domain/workspaceController';
import type {ActivityItem, ApplicationFilters} from './data/workspaceApi';
import {exportApi} from './data/exportApi';
import {createExportController} from './domain/exportController';
import type {User} from './data/api';
import { ThemeSelect } from './components/ThemeSelect';
// Limit navigation to the three workspace views.
type View='applications'|'interviews'|'followups';
// Retain the server version and previous data needed to safely undo each supported change.
type Undo={kind:'delete';deleted:Application}|{kind:'update';before:Application;version:number};
type Toast={message:string;undo?:Undo};
export default function App({user,onLogout}:{user:User;onLogout:()=>Promise<void>}) {
  // Read server-backed data and mutation handlers from the shared hook.
  const {workspace,controller,error,ready,busy,save,remove,restoreDeleted,refresh,loadDetail}=useApplications(user.id);
  // Track navigation and display controls separately from application records.
  const [view,setView]=useState<View>('applications'); const [query,setQuery]=useState('');
  const [tab,setTab]=useState<Tab>('All applications');const [status,setStatus]=useState('All statuses');
  const [sort,setSort]=useState('recent');const [dueOnly,setDueOnly]=useState(false);
  const [drawer,setDrawer]=useState<{id:string|null}|null>(null);const [mobileNav,setMobileNav]=useState(false);
  const [toast,setToast]=useState<Toast|null>(null);const searchRef=useRef<HTMLInputElement>(null);
  // Expire notifications, reset pagination on filter changes, and register the search shortcut.
  useEffect(()=>{if(!toast)return;const t=setTimeout(()=>setToast(null),toast.undo?12000:4500);return ()=>clearTimeout(t);},[toast]);
  // The controller debounces changes to query alone and applies other control changes immediately.
  useEffect(()=>{controller.setFilters({q:query,status:status==='All statuses'?undefined:status as Status,
    tab:tab==='In progress'?'active':tab==='Saved'?'saved':tab==='Closed'?'closed':'all',dueOnly,
    sort:(sort==='recent'?'updated':sort) as ApplicationFilters['sort'],
    kind:view==='applications'?undefined:view==='interviews'?'Interview':'Follow-up',size:view==='applications'?8:25});
  },[query,status,tab,sort,dueOnly,view,controller]);
  useEffect(()=>{function shortcut(e:KeyboardEvent){if((e.metaKey||e.ctrlKey)&&e.key==='k'){e.preventDefault();searchRef.current?.focus();}}window.addEventListener('keydown',shortcut);return()=>window.removeEventListener('keydown',shortcut);},[]);
  const apps=workspace.page?.content??[];
  const visible=apps;
  const focusedActivities=workspace.activityPage?.content??[];
  const counts=workspace.summary??{total:0,active:0,interviews:0,offers:0,due:0,scheduledInterviews:0,hasSamples:false};
  const due=counts.due;
  const [detail,setDetail]=useState<{application:Application|null;loading:boolean;error:string}>({application:null,loading:false,error:''});
  const [detailRetry,setDetailRetry]=useState(0);
  const exports=useMemo(()=>createExportController(exportApi),[user.id]);
  const exportStatus=useSyncExternalStore(exports.subscribe,exports.getSnapshot);
  const exportActive=exportStatus.job?.state==='PENDING'||exportStatus.job?.state==='RUNNING';
  useEffect(()=>{
    const visible=()=>exports.setVisible(!document.hidden);visible();
    document.addEventListener('visibilitychange',visible);
    return()=>{document.removeEventListener('visibilitychange',visible);exports.clear();};
  },[exports]);
  useEffect(()=>{
    const id=drawer?.id;let current=true;
    setDetail({application:null,loading:!!id,error:''});
    if(id)void loadDetail(id,detailRetry>0).then(application=>{if(current)setDetail({application,loading:false,error:''});})
      .catch(e=>{if(current)setDetail({application:null,loading:false,error:e instanceof Error?e.message:'Could not load this application.'});});
    return()=>{current=false;};
  },[drawer?.id,detailRetry,loadDetail]);
  // Reset view-specific controls when moving between workspace sections.
  function navigate(next:View){setView(next);setQuery('');setDueOnly(false);setMobileNav(false);}
  // Return to the application table with a selected pipeline status.
  function filterStatus(s:string){navigate('applications');setTab('All applications');setStatus(s);}
  // Select the application whose detail drawer should open.
  function open(app:{id:string}){setDrawer({id:app.id});}
  // Close the editor and notify the user after a confirmed save.
  async function saveApplication(draft:ApplicationDraft,id?:string,version?:number){await save(draft,id,version);setDrawer(null);setToast({message:id?'Application updated. You’re all set.':'Application added. Here’s to your next opportunity.'});}
  // Keep the returned deleted record so undo uses the new server version.
  async function deleteApplication(app:Application){const deleted=await remove(app);setDrawer(null);setToast({message:`${app.company} application deleted.`,undo:{kind:'delete',deleted}});}
  // Complete an interview or clear a follow-up, saving the prior data for undo.
  async function completeActivity(activity:ActivityItem){try{const {before,updated}=await completeWorkspaceActivity(controller,activity,save);setToast({message:activity.kind==='Interview'?'Interview marked complete. Nice progress!':'Follow-up completed.',undo:{kind:'update',before,version:updated.version}});}catch(e){setToast({message:e instanceof Error?e.message:'Could not save this change.'});}}
  // Restore a deletion or submit the previous data with the post-change version.
  async function undoChange(){const action=toast?.undo;if(!action||busy)return;try{if(action.kind==='delete')await restoreDeleted(action.deleted);else await save(action.before,action.before.id,action.version);setToast({message:'Change undone.'});}catch(e){setToast({message:e instanceof Error?e.message:'Unable to undo.'});}}
  // Display logout failures without discarding the current workspace.
  async function signOut(){try{await onLogout();}catch(e){setToast({message:e instanceof Error?e.message:'Could not sign out.'});}}
  async function downloadCSV(){
    const blob=await exports.download();if(!blob)return;
    const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='campus-applications-'+today()+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  // Keep the separately fetched editor record stable while read caches refresh.
  const selected=detail.application;
  const heading=view==='applications'?'Your next chapter starts here.':view==='interviews'?'Turn conversations into opportunities.':'A little follow-up goes a long way.';
  // Render navigation, summaries, the current table or activity list, and modal feedback.
  return <div className="app-shell">
    {mobileNav&&<button className="nav-backdrop" aria-label="Close navigation" onClick={()=>setMobileNav(false)}/>}
    {/* Render workspace navigation, status shortcuts, and the signed-in profile. */}
    <aside className={'sidebar '+(mobileNav?'is-open':'')}><a className="brand" href="#" onClick={e=>{e.preventDefault();navigate('applications');setTab('All applications');setStatus('All statuses');}}><span className="brand-symbol"><GraduationCap size={23}/></span><span>campus<span className="brand-dot">.</span></span></a>
      <div className="workspace-label">YOUR WORKSPACE</div><nav aria-label="Workspace"><button className={view==='applications'?'nav-item active':'nav-item'} onClick={()=>navigate('applications')}><LayoutGrid size={18}/><span>Applications</span><span className="nav-count">{counts.total}</span></button><button className={view==='interviews'?'nav-item active':'nav-item'} onClick={()=>navigate('interviews')}><CalendarDays size={18}/><span>Interviews</span>{counts.interviews>0&&<i className="nav-dot"/>}</button><button className={view==='followups'?'nav-item active':'nav-item'} onClick={()=>navigate('followups')}><Bell size={18}/><span>Follow-ups</span>{due>0&&<span className="due-count">{due}</span>}</button></nav>
      <div className="sidebar-divider"/><div className="workspace-label pipeline-label">YOUR PIPELINE</div><nav aria-label="Application statuses">{STATUSES.map(s=><button key={s} className={'pipeline-item '+(view==='applications'&&status===s?'selected':'')} onClick={()=>filterStatus(s)}><i className={'pipeline-dot '+s.toLowerCase()}/><span>{s}</span><span>{workspace.summary?.perStatus[s]??0}</span></button>)}</nav>
      <div className="sidebar-bottom"><div className="little-note"><Sparkles size={18}/><strong>Big things start small.</strong><p>One application. One conversation.<br/>One step closer.</p><span className="note-line"/></div><div className="profile"><span className="avatar">{user.displayName.split(/\s+/).slice(0,2).map(n=>n[0]).join('').toUpperCase()}</span><div><strong>{user.displayName}</strong><small>Personal workspace</small></div><LockKeyhole size={14}/></div></div>
    </aside>
    {/* Show the current view title and account controls above the workspace. */}
    <div className="main-shell"><header className="topbar"><div className="breadcrumbs"><button className="icon-button mobile-menu" aria-label="Open navigation" onClick={()=>setMobileNav(true)}><Menu size={20}/></button><span>Workspace</span><ChevronRight size={13}/><strong>{view==='applications'?'Applications':view==='interviews'?'Interviews':'Follow-ups'}</strong></div><div className="topbar-right"><ThemeSelect/><span className="private-pill"><LockKeyhole size={12}/> Private workspace</span><button className="text-button" disabled={busy} onClick={signOut}>Sign out</button><span className="topbar-date">{new Date().toLocaleDateString('en-US',{month:'short',day:'numeric',year:'numeric'})}</span></div></header>
      <main><section className="welcome"><div><div className="eyebrow"><span/> THE NEXT STEP IS YOURS</div><h1>{heading}</h1><p>Keep your opportunities organized. Focus on what comes next.</p></div><button className="button primary add-main" disabled={!ready||busy} onClick={()=>setDrawer({id:null})}><Plus size={17}/> Add application</button></section>
        {error&&<div className="error-box" role="alert">{error} <button className="text-button" disabled={busy} onClick={()=>void refresh()}>Retry connection</button></div>}
        {/* Use summary cards as shortcuts into the corresponding application filters. */}
        <section className="summary-grid" aria-label="Application summary">
          <button className="summary-card" onClick={()=>{filterStatus('All statuses');setTab('All applications');}}><div className="summary-label">Total applications<span className="summary-icon"><BriefcaseBusiness size={17}/></span></div><div className="summary-value">{counts.total}<span className="summary-trend"><ArrowUpRight size={14}/> Your opportunities</span></div><p>A little momentum, every day</p></button>
          <button className="summary-card" onClick={()=>{filterStatus('All statuses');setTab('In progress');}}><div className="summary-label">In progress<span className="summary-icon blue-icon"><Clock3 size={17}/></span></div><div className="summary-value">{counts.active}<span className="mini-bars">{Array.from({length:10},(_,i)=><i key={i} className={i<Math.round(counts.active/Math.max(counts.total,1)*10)?'filled':''}/>)}</span></div><p>Applied and moving through interviews</p></button>
          <button className="summary-card" onClick={()=>filterStatus('Interviewing')}><div className="summary-label">Interviewing<span className="summary-icon plum-icon"><CalendarDays size={17}/></span></div><div className="summary-value">{counts.interviews}<span className="summary-detail">{counts.scheduledInterviews} upcoming rounds</span></div><p>Your chance to make an impression</p></button>
          <button className="summary-card offer-card" onClick={()=>filterStatus('Offer')}><div className="summary-label">Offers received<span className="summary-icon"><Trophy size={17}/></span></div><div className="summary-value">{counts.offers}<span className="offer-star"><Sparkles size={24}/></span></div><p>{counts.offers?'Something worth celebrating':'The next chapter is ahead'}</p></button>
        </section>
        {/* Group search, filters, export, paging, and the selected table or activity view. */}
        <section className="workspace-panel" aria-labelledby="workspace-title" aria-busy={workspace.loading}><div className="panel-heading"><div className="panel-title"><h2 id="workspace-title">{view==='applications'?'Applications':view==='interviews'?'Interview schedule':'Follow-up list'}</h2><span className="count-badge">{view==='applications'?counts.total:focusedActivities.length}</span></div><div className="panel-actions"><span className="sample-label">{counts.hasSamples?'Includes sample data':'Your applications'}</span><button className="button secondary export-button" disabled={busy} onClick={()=>void refresh()}>Refresh</button><button className="button secondary export-button" onClick={()=>void exports.start()} disabled={!ready||busy||exportStatus.busy||exportActive}><ArrowDownToLine size={14}/> Export CSV</button></div></div>
          {view==='applications'&&<div className="tabs" role="tablist" aria-label="Application groups">{(['All applications','In progress','Saved','Closed'] as Tab[]).map(t=><button role="tab" aria-selected={tab===t} key={t} className={tab===t?'active':''} onClick={()=>{setTab(t);setStatus('All statuses');setDueOnly(false);}}>{t}{t==='All applications'&&<span>{counts.total}</span>}</button>)}</div>}
          <div className="toolbar"><div className="search-field"><Search size={17}/><input ref={searchRef} aria-label="Search applications" placeholder="Search company, role, or notes…" value={query} onChange={e=>setQuery(e.target.value)}/>{query?<button className="icon-button" aria-label="Clear search" onClick={()=>setQuery('')}><X size={14}/></button>:<kbd>Ctrl K</kbd>}</div>{view==='applications'&&<div className="filters"><label className="select-wrapper"><SlidersHorizontal size={14}/><select aria-label="Filter status" value={status} onChange={e=>setStatus(e.target.value)}><option>All statuses</option>{STATUSES.map(s=><option key={s}>{s}</option>)}</select><ChevronDown size={12}/></label><label className="select-wrapper sort-wrapper"><ArrowDown size={14}/><select aria-label="Sort applications" value={sort} onChange={e=>setSort(e.target.value)}><option value="recent">Last updated</option><option value="applied">Date applied</option><option value="deadline">Deadline</option><option value="company">Company A–Z</option></select><ChevronDown size={12}/></label></div>}</div>
          {(exportStatus.job||exportStatus.error||exportStatus.busy)&&<div className="export-status" role="status" aria-live="polite">
            <span>{exportStatus.error||exportStatus.message||(exportStatus.busy?'Starting your export…':'')}</span>
            {exportActive&&<button className="button secondary" disabled={exportStatus.busy} onClick={()=>void exports.cancel()}>Cancel export</button>}
            {exportStatus.job?.state==='READY'&&<button className="button secondary" disabled={exportStatus.downloading} onClick={()=>void downloadCSV()}>{exportStatus.downloading?'Downloading…':'Download CSV'}</button>}
            {exportActive&&exportStatus.error&&<button className="button secondary" onClick={()=>void exports.refresh()}>Check export status</button>}
          </div>}
          {dueOnly&&<div className="active-filter"><span>Follow-ups due today or overdue</span><button className="text-button" onClick={()=>setDueOnly(false)}>Clear <X size={13}/></button></div>}
          {workspace.summaryError&&<p className="error-box" role="alert">Counts could not load: {workspace.summaryError}</p>}{workspace.loading&&view==='applications'&&<p role="status">Loading applications…</p>}{/* Render bounded pages and on-demand detail actions. */}
          {view==='applications'?<><div className="table-scroll"><table><thead><tr><th>Company</th><th>Role</th><th>Status</th><th>Applied</th><th>Next step</th><th><span className="sr-only">Details</span></th></tr></thead><tbody>{visible.map(a=>{const next=a.nextActivity;return <tr key={a.id}><td><CompanyButton app={a} onOpen={open}/></td><td className="role-cell">{a.role}<span>Internship</span></td><td><StatusBadge status={a.status}/></td><td className="date-cell">{formatDate(a.appliedDate)}</td><td>{next?<button className={'next-step '+(next.date<=today()?'urgent':'')} onClick={()=>open(a)}><span>{next.kind==='Interview'?<CalendarDays size={12}/>:next.kind==='Deadline'?<Target size={12}/>:<Clock3 size={12}/>} {relativeDate(next.date)}</span><small>{next.kind==='Interview'?next.label:next.kind}</small></button>:<span className="no-step">{a.status==='Offer'?'Offer received':isClosed(a)?'Closed':'Nothing scheduled'}</span>}</td><td><button className="icon-button row-open" aria-label={`View ${a.company} details`} onClick={()=>open(a)}><ArrowUpRight size={16}/></button></td></tr>;})}</tbody></table></div>{!workspace.loading&&visible.length===0&&<div className="empty-state"><Search size={25}/><h3>No applications found</h3><p>{query||status!=='All statuses'||tab!=='All applications'||dueOnly?'Try a different search or clear your filters.':'Your next opportunity belongs here. Add your first application.'}</p><button className="button secondary" onClick={()=>{setQuery('');setStatus('All statuses');setTab('All applications');setDueOnly(false);if(!counts.total)setDrawer({id:null});}}>{counts.total?'Clear filters':'Add application'}</button></div>}<div className="table-footer"><span>{visible.length} on this page · {counts.total} total applications</span><div className="pagination"><button className="button secondary" disabled={!workspace.canPrevious||workspace.loading||busy} onClick={()=>void controller.previous()}>Previous</button><span>Page {workspace.pageNumber}</span><button className="button secondary" disabled={!workspace.canNext||workspace.loading||busy} onClick={()=>void controller.next()}>Next</button></div></div></>:<div className="activity-list">{workspace.loading?<p role="status">Loading activities…</p>:focusedActivities.length?focusedActivities.map(activity=><div className="activity-row" key={activity.id}><div className={'activity-date '+(dayDistance(activity.date)<0?'overdue':'')}><strong>{new Date(activity.date+'T12:00:00').getDate()}</strong><span>{new Date(activity.date+'T12:00:00').toLocaleDateString('en-US',{month:'short'})}</span></div><button className="activity-company" onClick={()=>open({id:activity.applicationId})}><strong>{activity.label}</strong><span>{activity.company} · {activity.role}</span></button><span className={'activity-due '+(dayDistance(activity.date)<0?'urgent':'')}>{relativeDate(activity.date)}</span><button className="button secondary complete-button" disabled={busy||!ready} aria-label={`Complete ${activity.company} ${activity.kind.toLowerCase()}`} onClick={()=>completeActivity(activity)}><Check size={15}/><span>Mark done</span></button></div>):<div className="empty-state"><CircleCheck size={28}/><h3>{query?'No matching items':'You’re all caught up.'}</h3><p>{view==='interviews'?'Schedule an interview in any application to see it here.':'Add a follow-up date to an application to keep the conversation going.'}</p></div>}</div>}
          {view!=='applications'&&<div className="table-footer"><span>{focusedActivities.length} on this page</span><div className="pagination"><button className="button secondary" disabled={!workspace.canPrevious||workspace.loading||busy} onClick={()=>void controller.previous()}>Previous</button><span>Page {workspace.pageNumber}</span><button className="button secondary" disabled={!workspace.canNext||workspace.loading||busy} onClick={()=>void controller.next()}>Next</button></div></div>}
        </section>
        {/* Highlight due follow-ups and the next interview as quick navigation targets. */}
        <section className="radar-section"><div className="radar-heading"><h2><CircleDot size={17}/> On your radar</h2><span>A little focus for the days ahead</span></div><div className="radar-grid"><button className="radar-card followup-radar" onClick={()=>{navigate('applications');setTab('All applications');setStatus('All statuses');setDueOnly(true);}}><span className="radar-icon"><Bell size={18}/></span><div><strong>{due?`${due} follow-up${due===1?'':'s'} need your attention`:'You’re up to date on follow-ups'}</strong><p>{due?'A quick check-in can keep things moving.':'Keep the conversation going at your own pace.'}</p></div><ArrowRight size={17}/></button><button className="radar-card" onClick={()=>navigate('interviews')}><span className="radar-icon interview-radar"><CalendarDays size={18}/></span><div><strong>{counts.scheduledInterviews?`${counts.scheduledInterviews} interview rounds scheduled`:'Room for your next conversation'}</strong><p>Open your interview schedule to see the next conversation.</p></div><ArrowUpRight size={17}/></button></div></section>
        {/* Report whether data is loading, saving, or unavailable. */}
        <footer className="page-footer"><span><span className={'save-dot '+(!ready?'warning':'')}/>{busy?'Saving…':ready?'Saved to your account':error?'Connection unavailable':'Loading your workspace…'}</span><span>Sample entries are illustrative · Your next opportunity is out there.</span></footer>
      </main>
    </div>
    {/* Mount a fresh editor for the selected record or a blank new application. */}
    {drawer&&<ApplicationDrawer key={drawer.id??'new'} application={selected??null} isNew={!drawer.id} loading={detail.loading} loadError={detail.error} onRetry={()=>setDetailRetry(value=>value+1)} onClose={()=>setDrawer(null)} onSave={saveApplication} onDelete={deleteApplication}/>}
    {/* Announce action results and expose the version-aware undo operation. */}
    {toast&&<div className="toast" role="status"><CircleCheck size={18}/><span>{toast.message}</span>{toast.undo&&<button disabled={busy} onClick={undoChange}>Undo</button>}<button className="icon-button" aria-label="Dismiss notification" onClick={()=>setToast(null)}><X size={15}/></button></div>}
  </div>;
}
