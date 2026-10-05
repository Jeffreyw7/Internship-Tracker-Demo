import {jsonBody,request,requestBlob} from './api.ts';

export type ExportState='PENDING'|'RUNNING'|'READY'|'FAILED'|'CANCELLED'|'EXPIRED';
export interface ExportJob {id:string;state:ExportState;createdAt:string;expiresAt:string;failureCode:string|null;downloadPath:string|null}
export const exportApi={
  create:(isCurrent:()=>boolean=()=>true)=>request<ExportJob>('/v2/exports',{method:'POST',...jsonBody({})},isCurrent),
  status:(id:string,isCurrent:()=>boolean=()=>true)=>request<ExportJob>('/v2/exports/'+encodeURIComponent(id),{},isCurrent),
  cancel:(id:string,isCurrent:()=>boolean=()=>true)=>request<ExportJob>('/v2/exports/'+encodeURIComponent(id),{method:'DELETE'},isCurrent),
  download:(id:string,isCurrent:()=>boolean=()=>true)=>requestBlob('/v2/exports/'+encodeURIComponent(id)+'/download',isCurrent),
};
