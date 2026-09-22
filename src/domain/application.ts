// Defines frontend application data, date helpers, validation, and the derived activity feed.
export const STATUSES = ['Saved', 'Applied', 'Interviewing', 'Offer', 'Rejected', 'Withdrawn'] as const;
export type Status = typeof STATUSES[number];
export type StageState = 'Planned' | 'Scheduled' | 'Completed';
export interface InterviewStage { id: string; name: string; date: string; state: StageState }
// Represent an API record including server-managed metadata used for safe updates.
export interface Application {
  id: string; company: string; role: string; location: string; status: Status;
  appliedDate: string; deadline: string; followUpDate: string; notes: string;
  stages: InterviewStage[]; createdAt: string; updatedAt: string;
  version: number; sample: boolean; deleted: boolean;
}
// Restrict editor drafts to fields users may change.
export type ApplicationDraft = Omit<Application, 'id' | 'createdAt' | 'updatedAt' | 'version' | 'sample' | 'deleted'>;
// Rejected and withdrawn applications no longer contribute scheduled activities.
export const isClosed = (a: Application) => ['Rejected', 'Withdrawn'].includes(a.status);
// Build local calendar dates without converting them to UTC.
export function today() { return localDate(new Date()); }
export function localDate(d: Date) { return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
// Generate dates relative to today for fixtures and sample displays.
export function offsetDate(offset: number) { const d = new Date(); d.setDate(d.getDate()+offset); return localDate(d); }
// Use local noon when parsing date-only values to avoid UTC date shifts.
export function formatDate(date: string) { return date ? new Date(date+'T12:00:00').toLocaleDateString('en-US',{month:'short',day:'numeric'}) : '—'; }
// Calculate calendar-day distance with rounding to absorb daylight-saving offsets.
export function dayDistance(date: string) { const d=new Date(date+'T12:00:00'); const n=new Date(today()+'T12:00:00'); return Math.round((d.getTime()-n.getTime())/86400000); }
// Turn dates into concise labels for upcoming and overdue activities.
export function relativeDate(date: string) { const n=dayDistance(date); return n===0?'Today':n===1?'Tomorrow':n<0?`${Math.abs(n)}d overdue`:formatDate(date); }
// Initialize an unsent saved opportunity with no dates or interview stages.
export function blankDraft(): ApplicationDraft { return {company:'',role:'',location:'',status:'Saved',appliedDate:'',deadline:'',followUpDate:'',notes:'',stages:[]}; }
// Catch common form errors before sending the draft to the server.
export function validateDraft(a: ApplicationDraft): string | null {
  if (!a.company.trim()) return 'Add a company name.';
  if (!a.role.trim()) return 'Add a role title.';
  if (a.status !== 'Saved' && !a.appliedDate) return 'Add the date you applied.';
  if (a.appliedDate && a.appliedDate > today()) return 'The application date cannot be in the future.';
  if (a.stages.some(s=>!s.name.trim())) return 'Give each interview stage a name.';
  if (a.stages.some(s=>s.state==='Scheduled' && !s.date)) return 'Choose a date for each scheduled interview.';
  return null;
}
// Unify interviews, follow-ups, and submission deadlines for shared UI displays.
export interface Activity { id:string; application:Application; kind:'Interview'|'Follow-up'|'Deadline'; label:string; date:string; stageId?:string }
// Derive actionable dated items, exclude closed applications, and sort chronologically.
export function activities(apps: Application[]): Activity[] {
  return apps.filter(a=>!isClosed(a)).flatMap(a=>[
    ...a.stages.filter(s=>s.date && s.state==='Scheduled').map(s=>({id:s.id,application:a,kind:'Interview' as const,label:s.name,date:s.date,stageId:s.id})),
    ...(a.followUpDate?[{id:a.id+'-follow',application:a,kind:'Follow-up' as const,label:'Check in with the team',date:a.followUpDate}]:[]),
    ...(a.status==='Saved' && a.deadline?[{id:a.id+'-deadline',application:a,kind:'Deadline' as const,label:'Submit your application',date:a.deadline}]:[])
  ]).sort((a,b)=>a.date.localeCompare(b.date));
}
// Check the expected runtime shape when validating an unknown application value.
export function validApplication(v: unknown): v is Application {
  if(!v || typeof v!=='object') return false;
  const a=v as Application;
  const keys=['id','company','role','location','appliedDate','deadline','followUpDate','notes','createdAt','updatedAt'] as const;
  const dateOK=(d:string)=>!d || (/^\d{4}-\d{2}-\d{2}$/.test(d) && !isNaN(new Date(d+'T12:00:00').getTime()));
  return Number.isSafeInteger(a.version) && a.version>=0 && typeof a.sample==='boolean' && typeof a.deleted==='boolean' && keys.every(k=>typeof a[k]==='string') && !!a.id && !!a.company.trim() && !!a.role.trim() && STATUSES.includes(a.status)
    && [a.appliedDate,a.deadline,a.followUpDate].every(dateOK) && Array.isArray(a.stages) && a.stages.every(s=>s && typeof s.id==='string' && typeof s.name==='string' && typeof s.date==='string' && dateOK(s.date) && ['Planned','Scheduled','Completed'].includes(s.state));
}
