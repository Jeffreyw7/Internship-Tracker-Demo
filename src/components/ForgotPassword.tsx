import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Mail } from 'lucide-react';
import { passwordResetApi } from '../data/passwordResetApi';
import { ThemeSelect } from './ThemeSelect';

export default function ForgotPassword({initialEmail, onReturn}: {initialEmail: string; onReturn: () => void}) {
  const [email,setEmail]=useState(initialEmail);
  const [busy,setBusy]=useState(false);
  const [accepted,setAccepted]=useState(false);
  const [error,setError]=useState('');
  const alive=useRef(true);
  useEffect(() => { alive.current=true; return () => { alive.current=false; }; },[]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if(busy) return;
    setBusy(true);setError('');
    try {
      await passwordResetApi.requestReset(email);
      if(alive.current) setAccepted(true);
    } catch(error) {
      if(alive.current) setError(error instanceof Error ? error.message : 'Unable to request a reset link. Please try again.');
    } finally { if(alive.current) setBusy(false); }
  }
  return <div className="auth-shell verification-shell"><div className="auth-appearance"><ThemeSelect/></div><section className="auth-card" aria-labelledby="forgot-password-title">
    <Mail className="verification-icon" size={28} aria-hidden="true"/>
    <h2 id="forgot-password-title">{accepted ? 'Check your inbox.' : 'Forgot your password?'}</h2>
    {accepted ? <p role="status">If recovery is available for this address, check your inbox for a reset link.</p> : <p>Enter your account email to request a password reset link. Recovery requires access to that mailbox.</p>}
    <form className="application-form auth-form" onSubmit={submit}>
      <label>Account email<input type="email" required maxLength={254} autoComplete="email" value={email} onChange={event => {setEmail(event.target.value);setAccepted(false);}} disabled={busy}/></label>
      {accepted && <p className="field-note">Check the address and your spam folder. Delivery can be delayed or limited. Wait a few minutes before explicitly requesting another link; your existing unexpired links remain usable.</p>}
      {error && <div className="error-box" role="alert">{error}</div>}
      <button className="button primary" type="submit" disabled={busy}>{busy ? 'Requesting…' : accepted ? 'Request another reset link' : 'Request reset link'}</button>
    </form>
    <button className="text-button auth-switch" disabled={busy} onClick={onReturn}>Back to sign in</button>
  </section></div>;
}
