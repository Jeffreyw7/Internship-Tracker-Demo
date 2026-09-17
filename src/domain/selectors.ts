import {isClosed, today, type Application} from './application.ts';
export type Tab='All applications'|'In progress'|'Saved'|'Closed';
export interface Filters {query:string;status:string;dueOnly:boolean;tab:Tab;sort:string}
export function matchesQuery(a:Application,query:string){return [a.company,a.role,a.location,a.notes,...a.stages.map(s=>s.name)].some(s=>s.toLowerCase().includes(query.toLowerCase().trim()));}
export function filterApplications(apps:Application[],{query,status,dueOnly,tab,sort}:Filters){
  return apps.filter(a=>matchesQuery(a,query)&&(status==='All statuses'||a.status===status)&&(!dueOnly||a.followUpDate&&a.followUpDate<=today()&&!isClosed(a))&&(tab==='All applications'||tab==='In progress'&&['Applied','Interviewing'].includes(a.status)||tab==='Saved'&&a.status==='Saved'||tab==='Closed'&&['Offer','Rejected','Withdrawn'].includes(a.status)))
    .sort((a,b)=>sort==='company'?a.company.localeCompare(b.company):sort==='deadline'?(a.deadline||'9999').localeCompare(b.deadline||'9999'):sort==='applied'?(b.appliedDate||'').localeCompare(a.appliedDate||''):b.updatedAt.localeCompare(a.updatedAt));
}
