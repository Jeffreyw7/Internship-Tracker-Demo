import type {Application} from './application.ts';
import {blankPostingDetails} from './postingDetails.ts';
function csvCell(value:string) {
  const protectedValue=/^[\s]*[=+\-@]|^[\t\r]/.test(value)?"'"+value:value;
  return '"'+protectedValue.replaceAll('"','""')+'"';
}
export function applicationCsv(apps:Application[]) {
  const header=['Company','Role','Location','Status','Application date','Deadline','Follow-up date','Interview stages','Notes',
    'Posting URL','Recruiter name','Recruiter role','Recruiter email','Recruiter phone','Recruiter profile URL','Referral channel','Referral source',
    'Referrer name','Referrer contact','Pay status','Compensation minimum','Compensation maximum','Currency','Pay period','Compensation wording'];
  const rows=apps.map(a=>{
    const p=a.postingDetails??blankPostingDetails();
    return [a.company,a.role,a.location,a.status,a.appliedDate,a.deadline,a.followUpDate,
      a.stages.map(s=>`${s.name} | ${s.state} | ${s.date}`).join('; '),a.notes,p.postingUrl,p.recruiterName,p.recruiterRole,p.recruiterEmail,
      p.recruiterPhone,p.recruiterProfileUrl,p.referralChannel,p.referralSource,p.referrerName,p.referrerContact,p.compensationStatus,
      p.compensationMin??'',p.compensationMax??'',p.compensationCurrency,p.compensationPeriod??'',p.compensationOriginalText];
  });
  return '\uFEFF'+[header,...rows].map(row=>row.map(csvCell).join(',')).join('\r\n');
}
export function downloadJson(value:unknown,filename:string) {
  const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download=filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
