import {REFERRAL_CHANNELS,PAY_PERIODS,type PostingDraft} from '../domain/postingDetails';
interface Props {
  value:PostingDraft;onChange:(value:PostingDraft)=>void;descriptionValue:string;descriptionAvailable:boolean;
  descriptionError:string;onRetry:()=>void;
}
const label=(value:string)=>value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase());
export function PostingFields({value:p,onChange,descriptionValue,descriptionAvailable,descriptionError,onRetry}:Props) {
  function update<K extends keyof PostingDraft>(key:K,value:PostingDraft[K]) {onChange({...p,[key]:value});}
  return <>
    <div className="form-section"><h3>Job posting</h3>
      <label>Posting URL<input type="url" maxLength={2048} placeholder="https://company.com/jobs/intern" value={p.postingUrl} onChange={e=>update('postingUrl',e.target.value)}/></label>
      <label>Saved description<textarea rows={7} maxLength={100000} disabled={!descriptionAvailable} value={descriptionValue} placeholder="Paste the posting here to keep it after the link disappears." onChange={e=>update('descriptionSnapshot',e.target.value)}/></label>
      <small className="field-note">Description changes create saved versions. Clearing this field keeps earlier versions in history.</small>
      {!descriptionAvailable&&<div className="posting-message" role="status">{descriptionError||'Loading the saved description…'} <button type="button" className="text-button" onClick={onRetry}>Retry description</button></div>}
    </div>
    <div className="form-section"><h3>Recruiter contact</h3><div className="form-grid">
      <label>Recruiter name<input maxLength={120} value={p.recruiterName} onChange={e=>update('recruiterName',e.target.value)}/></label>
      <label>Recruiter role<input maxLength={120} value={p.recruiterRole} onChange={e=>update('recruiterRole',e.target.value)}/></label>
      <label>Recruiter email<input type="email" maxLength={254} value={p.recruiterEmail} onChange={e=>update('recruiterEmail',e.target.value)}/></label>
      <label>Recruiter phone<input type="tel" maxLength={50} value={p.recruiterPhone} onChange={e=>update('recruiterPhone',e.target.value)}/></label>
    </div><label>Recruiter profile URL<input type="url" maxLength={2048} value={p.recruiterProfileUrl} onChange={e=>update('recruiterProfileUrl',e.target.value)}/></label></div>
    <div className="form-section"><h3>Referral source</h3><div className="form-grid">
      <label>Referral channel<select value={p.referralChannel} onChange={e=>update('referralChannel',e.target.value as PostingDraft['referralChannel'])}>{REFERRAL_CHANNELS.map(c=><option key={c} value={c}>{c==='UNKNOWN'?'Not specified':label(c)}</option>)}</select></label>
      <label>Referral source<input maxLength={200} placeholder="Job board, event, or team" value={p.referralSource} onChange={e=>update('referralSource',e.target.value)}/></label>
      <label>Referrer name<input maxLength={120} value={p.referrerName} onChange={e=>update('referrerName',e.target.value)}/></label>
      <label>Referrer contact<input maxLength={254} value={p.referrerContact} onChange={e=>update('referrerContact',e.target.value)}/></label>
    </div></div>
    <div className="form-section"><h3>Compensation</h3>
      <label>Pay status<select value={p.compensationStatus} onChange={e=>{const status=e.target.value as PostingDraft['compensationStatus'];onChange({...p,compensationStatus:status,...(status!=='PAID'?{compensationMin:null,compensationMax:null}:{})});}}><option value="UNKNOWN">Not specified</option><option value="PAID">Paid</option><option value="UNPAID">Unpaid</option></select></label>
      <div className="form-grid">
        <label>Minimum compensation<input inputMode="decimal" disabled={p.compensationStatus!=='PAID'} placeholder="e.g. 25.00" value={p.compensationMin??''} onChange={e=>update('compensationMin',e.target.value||null)}/></label>
        <label>Maximum compensation<input inputMode="decimal" disabled={p.compensationStatus!=='PAID'} placeholder="e.g. 35.00" value={p.compensationMax??''} onChange={e=>update('compensationMax',e.target.value||null)}/></label>
        <label>Currency<input maxLength={3} placeholder="USD" value={p.compensationCurrency} onChange={e=>update('compensationCurrency',e.target.value.toUpperCase())}/></label>
        <label>Pay period<select value={p.compensationPeriod??''} onChange={e=>update('compensationPeriod',(e.target.value||null) as PostingDraft['compensationPeriod'])}><option value="">Not specified</option>{PAY_PERIODS.map(c=><option key={c} value={c}>{label(c)}</option>)}</select></label>
      </div><label>Original compensation wording<textarea rows={2} maxLength={1000} placeholder="Include housing, stipend, bonus, or other details." value={p.compensationOriginalText} onChange={e=>update('compensationOriginalText',e.target.value)}/></label>
    </div>
  </>;
}
