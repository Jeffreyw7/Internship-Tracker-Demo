export const REFERRAL_CHANNELS=['UNKNOWN','JOB_BOARD','COMPANY_WEBSITE','CAREER_FAIR','EMPLOYEE','PROFESSOR','OTHER'] as const;
export const PAY_PERIODS=['HOUR','MONTH','YEAR','TOTAL_INTERNSHIP'] as const;
export interface PostingDetails {
  postingUrl:string; recruiterName:string; recruiterRole:string; recruiterEmail:string; recruiterPhone:string; recruiterProfileUrl:string;
  referralChannel:typeof REFERRAL_CHANNELS[number]; referralSource:string; referrerName:string; referrerContact:string;
  compensationMin:string|null; compensationMax:string|null; compensationCurrency:string;
  compensationPeriod:typeof PAY_PERIODS[number]|null; compensationStatus:'UNKNOWN'|'PAID'|'UNPAID'; compensationOriginalText:string;
  descriptionRevision:number;
}
export type PostingDraft=Omit<PostingDetails,'descriptionRevision'> & {descriptionSnapshot?:string};
export function blankPostingDetails():PostingDetails {
  return {postingUrl:'',recruiterName:'',recruiterRole:'',recruiterEmail:'',recruiterPhone:'',recruiterProfileUrl:'',
    referralChannel:'UNKNOWN',referralSource:'',referrerName:'',referrerContact:'',compensationMin:null,compensationMax:null,
    compensationCurrency:'',compensationPeriod:null,compensationStatus:'UNKNOWN',compensationOriginalText:'',descriptionRevision:0};
}
const textFields={postingUrl:2048,recruiterName:120,recruiterRole:120,recruiterEmail:254,recruiterPhone:50,recruiterProfileUrl:2048,
  referralSource:200,referrerName:120,referrerContact:254,compensationOriginalText:1000} as const;
export function validatePostingDraft(p:PostingDraft):string|null {
  for(const [key,max] of Object.entries(textFields)) if(typeof p[key as keyof typeof textFields]!=='string'||p[key as keyof typeof textFields].length>max)return `Check ${key} (maximum ${max} characters).`;
  for(const link of [p.postingUrl,p.recruiterProfileUrl]) {
    if(!link.trim())continue;
    try {const u=new URL(link);if(!['http:','https:'].includes(u.protocol)||!u.hostname||u.username||u.password||/[\x00-\x1f\x7f]/.test(link))return 'Use an HTTP or HTTPS URL without embedded credentials.';}
    catch{return 'Enter a valid HTTP or HTTPS URL.';}
  }
  if(p.recruiterEmail.trim()&&!/^[^\s@]+@[^\s@]+$/.test(p.recruiterEmail.trim()))return 'Enter a valid recruiter email.';
  if(!REFERRAL_CHANNELS.includes(p.referralChannel)||!['UNKNOWN','PAID','UNPAID'].includes(p.compensationStatus))return 'Choose a valid referral source and pay status.';
  if(p.compensationPeriod!==null&&!PAY_PERIODS.includes(p.compensationPeriod))return 'Choose a valid pay period.';
  const amounts=[p.compensationMin,p.compensationMax];
  if(amounts.some(v=>v!==null&&!/^\d{1,12}(\.\d{1,2})?$/.test(v)))return 'Compensation needs a nonnegative amount with at most two decimal places.';
  if(p.compensationMin!==null&&p.compensationMax!==null&&decimalCents(p.compensationMin)>decimalCents(p.compensationMax))return 'Compensation minimum cannot exceed maximum.';
  if(p.compensationCurrency.trim()&&!/^[A-Za-z]{3}$/.test(p.compensationCurrency.trim()))return 'Use a three-letter currency code.';
  if(amounts.some(v=>v!==null)&&(p.compensationStatus!=='PAID'||!p.compensationCurrency.trim()||!p.compensationPeriod))return 'Compensation amounts need paid status, a currency, and a pay period.';
  if(p.descriptionSnapshot!==undefined&&(typeof p.descriptionSnapshot!=='string'||p.descriptionSnapshot.length>100000))return 'Description must be text of at most 100,000 characters.';
  return null;
}
function decimalCents(value:string) {const [whole,fraction='']=value.split('.');return BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));}
export function postingPayload(p:PostingDraft) {
  const error=validatePostingDraft(p);if(error)throw new Error(error);
  const text=Object.fromEntries(Object.keys(textFields).map(key=>[key,p[key as keyof typeof textFields].trim()])) as Pick<PostingDraft,keyof typeof textFields>;
  return {...text,referralChannel:p.referralChannel,compensationMin:p.compensationMin,compensationMax:p.compensationMax,
    compensationCurrency:p.compensationCurrency.trim().toUpperCase(),compensationPeriod:p.compensationPeriod,compensationStatus:p.compensationStatus,
    ...(p.descriptionSnapshot!==undefined?{descriptionSnapshot:p.descriptionSnapshot}:{})};
}
export function compensationLabel(p:PostingDraft) {
  if(p.compensationStatus==='UNPAID')return 'Unpaid';
  const amounts=p.compensationMin!==null&&p.compensationMax!==null?`${p.compensationMin}–${p.compensationMax}`:
    p.compensationMin!==null?`From ${p.compensationMin}`:p.compensationMax!==null?`Up to ${p.compensationMax}`:'';
  if(!amounts)return p.compensationStatus==='PAID'?'Paid · range not provided':'Compensation not provided';
  const units={HOUR:'per hour',MONTH:'per month',YEAR:'per year',TOTAL_INTERNSHIP:'total internship'};
  return `${p.compensationCurrency} ${amounts}${p.compensationPeriod?' '+units[p.compensationPeriod]:''}`;
}
