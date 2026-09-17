import { type Application, type ApplicationDraft, validateDraft } from '../domain/application.ts';
import { request, jsonBody } from './api.ts';
interface ApplicationPage {content:Application[];totalElements:number;totalPages:number;page:number;size:number}
export function toPayload(a:ApplicationDraft) {
  const error=validateDraft(a);if(error)throw new Error(error);
  return {company:a.company.trim(),role:a.role.trim(),location:a.location.trim(),status:a.status,
    appliedDate:a.appliedDate||null,deadline:a.deadline||null,followUpDate:a.followUpDate||null,notes:a.notes,
    stages:a.stages.map(s=>({id:s.id||null,name:s.name.trim(),date:s.date||null,state:s.state}))};
}
export const applicationApi={
  async list():Promise<Application[]> {
    // Cache personal history for instant cross-view filtering; the REST API supports paginated server queries.
    const first=await request<ApplicationPage>('/applications?size=100&page=0');
    const apps=[...first.content];
    for(let page=1;page<first.totalPages;page++) {
      const next=await request<ApplicationPage>(`/applications?size=100&page=${page}`);apps.push(...next.content);
    }
    return [...new Map(apps.map(a=>[a.id,a])).values()];
  },
  save(draft:ApplicationDraft,id?:string,version?:number):Promise<Application> {
    const init=jsonBody(toPayload(draft));
    return request<Application>(id?`/applications/${id}`:'/applications',{
      ...init,method:id?'PUT':'POST',headers:{...init.headers,...(id?{'If-Match':String(version)}:{})}
    });
  },
  remove(app:Application){return request<Application>(`/applications/${app.id}`,{method:'DELETE',headers:{'If-Match':String(app.version)}});},
  restoreDeleted(app:Application){return request<Application>(`/applications/${app.id}/restore`,{method:'POST',headers:{'If-Match':String(app.version)}});}
};
