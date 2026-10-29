import type {Application} from './application.ts';
import type {ImportPreview,ImportReceipt} from './applicationImport.ts';
// Spreadsheet programs differ: prefixing formula-leading literals reduces risk, but may add an apostrophe on reimport.
export function csvCell(value:unknown):string {
  const text=String(value??'');const safe=/^[\s\x00-\x20\x7f-\x9f]*[=+@-]/.test(text)?"'"+text:text;
  return '"'+safe.replaceAll('"','""')+'"';
}
export const csvRows=(rows:unknown[][])=>rows.map(row=>row.map(csvCell).join(',')).join('\r\n');
export function applicationCsv(apps:Application[]):string {
  return csvRows([['Application ID','Company','Role','Location','Status','Applied date','Deadline','Follow-up date','Notes','Interview stages JSON'],...apps.map(a=>[a.id,a.company,a.role,a.location,a.status,a.appliedDate,a.deadline,a.followUpDate,a.notes,JSON.stringify(a.stages.map(s=>({name:s.name,date:s.date||null,state:s.state})))])]);
}
export function importReport(value:ImportPreview|ImportReceipt):string {
  if('receiptId' in value)return csvRows([['Row','Action','Application ID','Result version'],...value.rows.map(r=>[r.rowNumber,r.action,r.applicationId,r.resultVersion])]);
  return csvRows([['Row','Action','Target ID','Issues','Warnings','Differences'],...value.rows.map(r=>[r.rowNumber,r.action,r.targetId,r.issues.map(i=>`${i.field}: ${i.message}`).join('\n'),r.warnings.join('\n'),r.differences.map(d=>`${d.field}: ${d.before} → ${d.after}`).join('\n')])]);
}
export function downloadCsv(contents:string,filename:string) {
  const url=URL.createObjectURL(new Blob(['\uFEFF'+contents],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
}
