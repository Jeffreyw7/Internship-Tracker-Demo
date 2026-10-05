import type {Application, Status} from '../domain/application.ts';
import {request, sessionGuard} from './api.ts';

export type ActivityKind='Interview'|'Follow-up'|'Deadline';
export interface NextActivity {id:string;kind:ActivityKind;label:string;date:string;stageId:string|null}
export interface ApplicationListItem {
  id:string;company:string;role:string;location:string;status:Status;appliedDate:string;deadline:string;
  followUpDate:string;createdAt:string;updatedAt:string;version:number;sample:boolean;nextActivity:NextActivity|null;
}
export interface CursorPage<T> {content:T[];nextCursor:string|null;hasNext:boolean;size:number;readAt:string}
export interface ApplicationFilters {
  q:string;status?:Status;tab:'all'|'active'|'saved'|'closed';dueOnly:boolean;today:string;sort:'updated'|'company'|'deadline'|'applied';size:number;cursor?:string|null;
}
export interface ActivityFilters {kind?:ActivityKind;q:string;today?:string;size:number;cursor?:string|null}
export interface ActivityItem {
  id:string;applicationId:string;company:string;role:string;location:string;applicationStatus:Status;
  applicationVersion:number;kind:ActivityKind;label:string;date:string;stageId:string|null;
}
export interface WorkspaceSummary {
  total:number;active:number;interviews:number;offers:number;due:number;scheduledInterviews:number;
  hasSamples:boolean;perStatus:Record<Status,number>;readAt:string;
}
function query(values:object) {
  const params=new URLSearchParams();
  for(const [key,value] of Object.entries(values))if(value!==undefined&&value!==null)params.set(key,String(value));
  return params.toString();
}
export const workspaceApi={
  list:(filters:ApplicationFilters,isCurrent:()=>boolean=sessionGuard())=>request<CursorPage<ApplicationListItem>>('/v2/applications?'+query(filters),{},isCurrent),
  summary:(today:string,isCurrent:()=>boolean=sessionGuard())=>request<WorkspaceSummary>('/v2/applications/summary?'+query({today}),{},isCurrent),
  activities:(filters:ActivityFilters,isCurrent:()=>boolean=sessionGuard())=>request<CursorPage<ActivityItem>>('/v2/activities?'+query(filters),{},isCurrent),
  detail:(id:string,isCurrent:()=>boolean=sessionGuard())=>request<Application>('/applications/'+encodeURIComponent(id),{},isCurrent)
};
