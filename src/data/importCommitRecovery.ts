import {ApiError,assertCurrent} from './api.ts';
import {ImportOutcomeUnknown} from './applicationImportApi.ts';
import type {ImportPreview,ImportReceipt} from '../domain/applicationImport.ts';
interface Dependencies {current:()=>boolean;remember:()=>void;release:()=>void;commit:(preview:ImportPreview,current:()=>boolean)=>Promise<ImportReceipt>;complete:(receipt:ImportReceipt,current:()=>boolean)=>Promise<ImportReceipt>}
// A rejection of a retry says nothing about the earlier dispatched transaction.
export async function commitReviewedImport(deps:Dependencies,preview:ImportPreview,retry:boolean):Promise<ImportReceipt> {
  assertCurrent(deps.current);deps.remember();
  try {return await deps.complete(await deps.commit(preview,deps.current),deps.current);}
  catch(e){
    const transition=e instanceof ApiError&&['IMPORT_ALREADY_COMPLETED','IMPORT_PREVIEW_SUPERSEDED'].includes(e.code??'');
    if(deps.current()&&!retry&&!(e instanceof ImportOutcomeUnknown)&&!transition)deps.release();
    throw e;
  }
}
