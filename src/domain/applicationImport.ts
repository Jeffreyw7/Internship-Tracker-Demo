import {STATUSES, type Status, type StageState} from './application.ts';

export const IMPORT_FIELDS=['company','role','location','status','appliedDate','deadline','followUpDate','notes','stages','applicationId','legacyStages','ignore'] as const;
export type ImportField=typeof IMPORT_FIELDS[number];
export type DateFormat='ISO'|'MDY'|'DMY';
export interface RowOverride {rowNumber:number;action?:'Add'|'Update'|'Skip'|null;targetId?:string|null;values?:Record<string,string>;clears?:string[];stageMode?:'KEEP'|'REPLACE';acknowledgeLegacy?:boolean}
export interface ImportOptions {schemaVersion:1;mapping:Record<string,string>;dateFormat:DateFormat;rows:RowOverride[];supersedesPreviewId?:string|null}
export interface ImportIssue {code:string;field:string;message:string}
export interface ImportCounts {added:number;updated:number;skipped:number;unchanged:number;errors:number}
export interface ImportCandidate {id:string;company:string;role:string;location:string}
export interface EditableRecord {company:string;role:string;location:string;status:Status;appliedDate:string|null;deadline:string|null;followUpDate:string|null;notes:string;stages:{id?:string|null;name:string;date:string|null;state:StageState}[]}
export interface ImportRow {rowNumber:number;cells:string[];action:'Add'|'Update'|'Skip'|'Unchanged';targetId:string|null;targetVersion:number|null;targetSummary:ImportCandidate|null;candidates:ImportCandidate[];moreCandidates:boolean;intents:Record<string,{intent:'KEEP'|'SET'|'CLEAR';value:string|null}>;before:EditableRecord|null;after:EditableRecord|null;differences:{field:string;before:string;after:string}[];issues:ImportIssue[];warnings:string[]}
export interface ImportPreview {id:string;schemaVersion:1;policyVersion:string;createdAt:string;expiresAt:string;reviewDigest:string;committable:boolean;headers:string[];rows:ImportRow[];counts:ImportCounts;issues:ImportIssue[]}
export interface ImportReceipt {importId:string;receiptId:string;completedAt:string;retainUntil:string;counts:ImportCounts;rows:{rowNumber:number;action:string;applicationId:string|null;resultVersion:number|null}[]}
export type ImportStatus={status:'PREVIEW';preview:ImportPreview}|{status:'COMPLETED';receipt:ImportReceipt}|{status:'SUPERSEDED';successorId:string}|{status:'UNRESOLVED';code?:string|null};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const string=(v:unknown):v is string=>typeof v==='string';
const integer=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=0;
const nullableString=(v:unknown)=>v===null||string(v);
const strings=(v:unknown):v is string[]=>Array.isArray(v)&&v.every(string);
const timestamp=(v:unknown)=>string(v)&&v.length<=40&&Number.isFinite(Date.parse(v));
const uuid=(v:unknown):v is string=>string(v)&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const date=(v:unknown)=>v===null||(string(v)&&/^\d{4}-\d{2}-\d{2}$/.test(v));
const counts=(v:unknown)=>object(v)&&['added','updated','skipped','unchanged','errors'].every(k=>integer(v[k]));
const issue=(v:unknown)=>object(v)&&string(v.code)&&string(v.field)&&string(v.message);
const candidate=(v:unknown)=>object(v)&&uuid(v.id)&&['company','role','location'].every(k=>string(v[k]));
const editable=(v:unknown)=>object(v)&&['company','role','location','notes'].every(k=>string(v[k]))&&STATUSES.includes(v.status as Status)&&['appliedDate','deadline','followUpDate'].every(k=>date(v[k]))&&Array.isArray(v.stages)&&v.stages.length<=30&&v.stages.every(s=>object(s)&&string(s.name)&&date(s.date)&&['Planned','Scheduled','Completed'].includes(String(s.state))&&(s.id===undefined||nullableString(s.id)));
const row=(v:unknown)=>object(v)&&integer(v.rowNumber)&&Number(v.rowNumber)>0&&strings(v.cells)&&v.cells.length<=32&&['Add','Update','Skip','Unchanged'].includes(String(v.action))&&(v.targetId===null||uuid(v.targetId))&&(v.targetVersion===null||integer(v.targetVersion))&&(v.targetSummary===null||candidate(v.targetSummary))&&Array.isArray(v.candidates)&&v.candidates.length<=5&&v.candidates.every(candidate)&&typeof v.moreCandidates==='boolean'&&object(v.intents)&&Object.values(v.intents).every(i=>object(i)&&['KEEP','SET','CLEAR'].includes(String(i.intent))&&nullableString(i.value))&&(v.before===null||editable(v.before))&&(v.after===null||editable(v.after))&&Array.isArray(v.differences)&&v.differences.every(d=>object(d)&&string(d.field)&&string(d.before)&&string(d.after))&&Array.isArray(v.issues)&&v.issues.every(issue)&&strings(v.warnings);
function invalid():never {throw new Error('The import response was incomplete or invalid. Check its status before continuing.');}
export function parsePreview(v:unknown):ImportPreview {
  if(!object(v)||!uuid(v.id)||v.schemaVersion!==1||!string(v.policyVersion)||!timestamp(v.createdAt)||!timestamp(v.expiresAt)||!string(v.reviewDigest)||!v.reviewDigest||typeof v.committable!=='boolean'||!strings(v.headers)||v.headers.length>32||!Array.isArray(v.rows)||v.rows.length>500||!v.rows.every(row)||!counts(v.counts)||!Array.isArray(v.issues)||!v.issues.every(issue))return invalid();
  return v as unknown as ImportPreview;
}
export function parseReceipt(v:unknown):ImportReceipt {
  if(!object(v)||!uuid(v.importId)||!uuid(v.receiptId)||!timestamp(v.completedAt)||!timestamp(v.retainUntil)||!counts(v.counts)||!Array.isArray(v.rows)||v.rows.length>500||!v.rows.every(r=>object(r)&&integer(r.rowNumber)&&Number(r.rowNumber)>0&&['Add','Update','Skip','Unchanged'].includes(String(r.action))&&(r.applicationId===null||uuid(r.applicationId))&&(r.resultVersion===null||integer(r.resultVersion))))return invalid();
  return v as unknown as ImportReceipt;
}
export function parseImportStatus(v:unknown):ImportStatus {
  if(!object(v))return invalid();
  if(v.status==='PREVIEW')return {status:'PREVIEW',preview:parsePreview(v.preview)};
  if(v.status==='COMPLETED')return {status:'COMPLETED',receipt:parseReceipt(v.receipt)};
  if(v.status==='SUPERSEDED'&&uuid(v.successorId))return {status:'SUPERSEDED',successorId:v.successorId};
  if(v.status==='UNRESOLVED'&&(v.code===undefined||nullableString(v.code)))return {status:'UNRESOLVED',code:v.code as string|null|undefined};
  return invalid();
}
export const IMPORT_POINTER_KEY='campus:pending-import';
type PointerStorage=Pick<Storage,'getItem'|'setItem'|'removeItem'>;
export interface ImportPointer {ownerId:string;previewId:string}
const ownerPointerKey=(ownerId:string)=>`${IMPORT_POINTER_KEY}:${encodeURIComponent(ownerId)}`;
function storedPointer(storage:PointerStorage|undefined,key:string):ImportPointer|null {
  const p:unknown=JSON.parse(storage?.getItem(key)??'null');
  return object(p)&&typeof p.ownerId==='string'&&uuid(p.previewId)?{ownerId:p.ownerId,previewId:p.previewId}:null;
}
function clearLegacyPointer(storage:PointerStorage|undefined,ownerId:string){
  if(storedPointer(storage,IMPORT_POINTER_KEY)?.ownerId===ownerId)storage?.removeItem(IMPORT_POINTER_KEY);
}
export function readImportPointer(storage:PointerStorage|undefined,ownerId:string):ImportPointer|null {
  try {
    const key=ownerPointerKey(ownerId),scoped=storedPointer(storage,key);
    if(scoped?.ownerId===ownerId)return scoped;
    const legacy=storedPointer(storage,IMPORT_POINTER_KEY);
    if(legacy?.ownerId===ownerId){
      // Do not remove the old reference unless the per-account replacement was persisted.
      try {storage?.setItem(key,JSON.stringify(legacy));storage?.removeItem(IMPORT_POINTER_KEY);} catch { /* Still return the readable legacy reference. */ }
      return legacy;
    }
  }catch{/* Storage is optional; server ownership remains authoritative. */}
  return null;
}
export function writeImportPointer(storage:PointerStorage|undefined,ownerId:string,previewId:string) {
  try {storage?.setItem(ownerPointerKey(ownerId),JSON.stringify({ownerId,previewId}));clearLegacyPointer(storage,ownerId);}
  catch{/* Recovery remains available by its visible reference. */}
}
export function clearImportPointer(storage:PointerStorage|undefined,ownerId:string) {
  try {storage?.removeItem(ownerPointerKey(ownerId));clearLegacyPointer(storage,ownerId);}
  catch{/* Private browsing may disable storage. */}
}
