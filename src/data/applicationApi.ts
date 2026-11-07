// Adapts editable frontend drafts and versioned API calls to persisted application records.
import { type Application, type ApplicationDraft, validateDraft, validApplication } from '../domain/application.ts';
import { ApiError, request, jsonBody } from './api.ts';
import { listApplicationHistory } from './applicationHistoryApi.ts';
// Whitelist editable fields and convert empty date inputs to JSON null.
export function toPayload(a:ApplicationDraft) {
  const error=validateDraft(a);if(error)throw new Error(error);
  return {company:a.company.trim(),role:a.role.trim(),location:a.location.trim(),status:a.status,
    appliedDate:a.appliedDate||null,deadline:a.deadline||null,followUpDate:a.followUpDate||null,notes:a.notes,
    stages:a.stages.map(s=>({id:s.id||null,name:s.name.trim(),date:s.date||null,state:s.state}))};
}
export const applicationApi={
  async get(id:string,isCurrent:()=>boolean=()=>true):Promise<Application>{
    const result=await request<unknown>(`/applications/${encodeURIComponent(id)}`,{},isCurrent);
    if(!validApplication(result)||result.id!==id)throw new ApiError(502,'The server returned an invalid application. Check the latest saved version again.');
    return result;
  },
  async list(guard:()=>boolean=()=>true):Promise<Application[]> {
    return listApplicationHistory(guard);
  },
  // Choose create or update and send the version header only for existing records.
  save(draft:ApplicationDraft,id?:string,version?:number,isCurrent:()=>boolean=()=>true):Promise<Application> {
    const init=jsonBody(toPayload(draft));
    return request<Application>(id?`/applications/${id}`:'/applications',{
      ...init,method:id?'PUT':'POST',headers:{...init.headers,...(id?{'If-Match':String(version)}:{})}
    },isCurrent);
  },
  // Use the last known version when deleting or restoring a record.
  remove(app:Application,isCurrent:()=>boolean=()=>true){return request<Application>(`/applications/${app.id}`,{method:'DELETE',headers:{'If-Match':String(app.version)}},isCurrent);},
  restoreDeleted(app:Application,isCurrent:()=>boolean=()=>true){return request<Application>(`/applications/${app.id}/restore`,{method:'POST',headers:{'If-Match':String(app.version)}},isCurrent);}
};
