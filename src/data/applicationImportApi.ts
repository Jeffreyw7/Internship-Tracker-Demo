import {ApiError,StaleSessionError,assertCurrent,jsonBody,request,sessionGuard,refreshCsrf} from './api.ts';
import {parsePreview,parseReceipt,parseImportStatus,type ImportOptions,type ImportPreview,type ImportReceipt,type ImportStatus} from '../domain/applicationImport.ts';
export class ImportOutcomeUnknown extends Error {constructor(cause:unknown){super('Completion is not yet confirmed. Check status before making another change.',{cause});this.name='ImportOutcomeUnknown';}}
const bound=(guard:()=>boolean)=>{const session=sessionGuard();return ()=>session()&&guard();};
export const applicationImportApi={
  async preview(file:File,options:ImportOptions,guard:()=>boolean=()=>true):Promise<ImportPreview> {
    const current=bound(guard);assertCurrent(current);
    if(file.size>2*1024*1024)throw new Error('Choose a CSV file no larger than 2 MiB.');
    const encoded=JSON.stringify(options);if(new Blob([encoded]).size>256*1024)throw new Error('Corrections exceed the 256 KiB limit.');
    const body=new FormData();body.append('file',file);body.append('options',new Blob([encoded],{type:'application/json'}));
    return parsePreview(await request<unknown>('/application-imports/preview',{method:'POST',body},current));
  },
  async commit(preview:ImportPreview,guard:()=>boolean=()=>true):Promise<ImportReceipt> {
    const current=bound(guard);assertCurrent(current);
    // Resolve CSRF before the uncertainty boundary; no import write has been dispatched yet.
    await refreshCsrf(current);assertCurrent(current);
    try {
      const receipt=parseReceipt(await request<unknown>(`/application-imports/${preview.id}/commit`,{method:'POST',...jsonBody({reviewDigest:preview.reviewDigest,acknowledgement:{existingApplicationsToUpdate:preview.counts.updated}})},current));
      if(receipt.importId!==preview.id)throw new Error('Mismatched import receipt.');return receipt;
    } catch(e) {
      if(e instanceof StaleSessionError)throw e;
      if(e instanceof ApiError&&e.status>=400&&e.status<500&&e.authoritative&&![401,403].includes(e.status))throw e;
      throw new ImportOutcomeUnknown(e);
    }
  },
  async status(id:string,guard:()=>boolean=()=>true):Promise<ImportStatus> {
    const current=bound(guard);assertCurrent(current);const result=parseImportStatus(await request<unknown>(`/application-imports/${id}`,{},current));
    if(result.status==='PREVIEW'&&result.preview.id!==id||result.status==='COMPLETED'&&result.receipt.importId!==id)throw new Error('Mismatched import status.');return result;
  }
};
