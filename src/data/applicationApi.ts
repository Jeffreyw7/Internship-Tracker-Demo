// Adapts editable frontend drafts and versioned API calls to persisted application records.
import { type Application, type ApplicationDraft, validateDraft } from '../domain/application.ts';
import { ApiError, request, jsonBody, sessionGuard } from './api.ts';
// Match the server\'s paginated response envelope.
interface ApplicationPage {content:Application[];totalElements:number;totalPages:number;page:number;size:number}
// Whitelist editable fields and convert empty date inputs to JSON null.
export function toPayload(a:ApplicationDraft) {
  const error=validateDraft(a);if(error)throw new Error(error);
  return {company:a.company.trim(),role:a.role.trim(),location:a.location.trim(),status:a.status,
    appliedDate:a.appliedDate||null,deadline:a.deadline||null,followUpDate:a.followUpDate||null,notes:a.notes,
    stages:a.stages.map(s=>({id:s.id||null,name:s.name.trim(),date:s.date||null,state:s.state}))};
}
export const applicationApi={
  async list(guard:()=>boolean=()=>true):Promise<Application[]> {
    const accountCurrent=sessionGuard();const isCurrent=()=>accountCurrent()&&guard();
    // An immutable creation order keeps ordinary edits from moving unread records behind the cursor.
    const first=await request<ApplicationPage>('/applications?sort=created&size=100&page=0',{},isCurrent);
    const apps=[...first.content];
    const changed=()=>new ApiError(409,'Your application history changed while loading. Refresh and try again.');
    for(let page=1;page<first.totalPages;page++) {
      const next=await request<ApplicationPage>(`/applications?sort=created&size=100&page=${page}`,{},isCurrent);
      if(next.totalElements!==first.totalElements||next.totalPages!==first.totalPages)throw changed();
      apps.push(...next.content);
    }
    // Concurrent additions/deletions must not turn a partial traversal into a successful export.
    if(apps.length!==first.totalElements||new Set(apps.map(a=>a.id)).size!==apps.length)throw changed();
    return apps;
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
