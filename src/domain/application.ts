export const STATUSES = ['Saved', 'Applied', 'Interviewing', 'Offer', 'Rejected', 'Withdrawn'] as const;
export type Status = typeof STATUSES[number];
export type StageState = 'Planned' | 'Scheduled' | 'Completed';
export interface InterviewStage { id: string; name: string; date: string; state: StageState }
export interface Application {
  id: string; company: string; role: string; location: string; status: Status;
  appliedDate: string; deadline: string; followUpDate: string; notes: string;
  stages: InterviewStage[]; createdAt: string; updatedAt: string;
  version: number; sample: boolean; deleted: boolean;
}
export type ApplicationDraft = Omit<Application, 'id' | 'createdAt' | 'updatedAt' | 'version' | 'sample' | 'deleted'>;
export const isClosed = (a: Application) => ['Rejected', 'Withdrawn'].includes(a.status);
export function today() { return localDate(new Date()); }
export function localDate(d: Date) { return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
export function offsetDate(offset: number) { const d = new Date(); d.setDate(d.getDate()+offset); return localDate(d); }
export function formatDate(date: string) { return date ? new Date(date+'T12:00:00').toLocaleDateString('en-US',{month:'short',day:'numeric'}) : '—'; }
export function dayDistance(date: string) { const d=new Date(date+'T12:00:00'); const n=new Date(today()+'T12:00:00'); return Math.round((d.getTime()-n.getTime())/86400000); }
export function relativeDate(date: string) { const n=dayDistance(date); return n===0?'Today':n===1?'Tomorrow':n<0?`${Math.abs(n)}d overdue`:formatDate(date); }
export function blankDraft(): ApplicationDraft { return {company:'',role:'',location:'',status:'Saved',appliedDate:'',deadline:'',followUpDate:'',notes:'',stages:[]}; }
export function validateDraft(a: ApplicationDraft): string | null {
  if (!a.company.trim()) return 'Add a company name.';
  if (!a.role.trim()) return 'Add a role title.';
  if (a.status !== 'Saved' && !a.appliedDate) return 'Add the date you applied.';
  if (a.appliedDate && a.appliedDate > today()) return 'The application date cannot be in the future.';
  if (a.stages.some(s=>!s.name.trim())) return 'Give each interview stage a name.';
  if (a.stages.some(s=>s.state==='Scheduled' && !s.date)) return 'Choose a date for each scheduled interview.';
  return null;
}
export interface Activity { id:string; application:Application; kind:'Interview'|'Follow-up'|'Deadline'; label:string; date:string; stageId?:string }
export function activities(apps: Application[]): Activity[] {
  return apps.filter(a=>!isClosed(a)).flatMap(a=>[
    ...a.stages.filter(s=>s.date && s.state==='Scheduled').map(s=>({id:s.id,application:a,kind:'Interview' as const,label:s.name,date:s.date,stageId:s.id})),
    ...(a.followUpDate?[{id:a.id+'-follow',application:a,kind:'Follow-up' as const,label:'Check in with the team',date:a.followUpDate}]:[]),
    ...(a.status==='Saved' && a.deadline?[{id:a.id+'-deadline',application:a,kind:'Deadline' as const,label:'Submit your application',date:a.deadline}]:[])
  ]).sort((a,b)=>a.date.localeCompare(b.date));
}
export function validApplication(v: unknown): v is Application {
  if(!v || typeof v!=='object') return false;
  const a=v as Application;
  const keys=['id','company','role','location','appliedDate','deadline','followUpDate','notes','createdAt','updatedAt'] as const;
  const dateOK=(d:string)=>!d || (/^\d{4}-\d{2}-\d{2}$/.test(d) && !isNaN(new Date(d+'T12:00:00').getTime()));
  return Number.isSafeInteger(a.version) && a.version>=0 && typeof a.sample==='boolean' && typeof a.deleted==='boolean' && keys.every(k=>typeof a[k]==='string') && !!a.id && !!a.company.trim() && !!a.role.trim() && STATUSES.includes(a.status)
    && [a.appliedDate,a.deadline,a.followUpDate].every(dateOK) && Array.isArray(a.stages) && a.stages.every(s=>s && typeof s.id==='string' && typeof s.name==='string' && typeof s.date==='string' && dateOK(s.date) && ['Planned','Scheduled','Completed'].includes(s.state));
}
