// Keeps client-side searching, tab filtering, and sorting independent from React rendering.
import {isClosed, today, type Application} from './application.ts';
export type Tab='All applications'|'In progress'|'Saved'|'Closed';
// Describe the active controls used to select the visible applications.
export interface Filters {query:string;status:string;dueOnly:boolean;tab:Tab;sort:string}
// Search opportunity fields, notes, and interview names without case sensitivity.
export function matchesQuery(a:Application,query:string){return [a.company,a.role,a.location,a.notes,...a.stages.map(s=>s.name)].some(s=>s.toLowerCase().includes(query.toLowerCase().trim()));}
// Combine all active filters and order the matching records for display.
export function filterApplications(apps:Application[],{query,status,dueOnly,tab,sort}:Filters){
  return apps.filter(a=>matchesQuery(a,query)&&(status==='All statuses'||a.status===status)&&(!dueOnly||a.followUpDate&&a.followUpDate<=today()&&!isClosed(a))&&(tab==='All applications'||tab==='In progress'&&['Applied','Interviewing'].includes(a.status)||tab==='Saved'&&a.status==='Saved'||tab==='Closed'&&['Offer','Rejected','Withdrawn'].includes(a.status)))
    .sort((a,b)=>sort==='company'?a.company.localeCompare(b.company):sort==='deadline'?(a.deadline||'9999').localeCompare(b.deadline||'9999'):sort==='applied'?(b.appliedDate||'').localeCompare(a.appliedDate||''):b.updatedAt.localeCompare(a.updatedAt));
}
