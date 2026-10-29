import { useEffect, useRef, useState, type FormEvent } from 'react';
import { LockKeyhole } from 'lucide-react';
import { type User } from '../data/api';
import { passwordResetApi } from '../data/passwordResetApi';
import { PASSWORD_REQUIREMENTS, passwordError } from '../domain/password';
import { ThemeSelect } from './ThemeSelect';

export function resetErrorMessage(error: {status?:number;code?:string;message?:string}): string {
  if(error.code === 'PASSWORD_RESET_INVALID') return 'This reset link is invalid, expired or already been used. Try signing in or request a new link.';
  if(error.status === 0 || error.status === 503) return 'We could not confirm the result. Your password may already have changed. Try signing in with your new password, or explicitly request another reset link.';
  return error.message || 'Unable to reset your password. Please try again.';
}

export default function ResetPassword({token,completed,user,onReturn,onRequest,onSignOut,onComplete}: {
  token: string | null;
  completed: boolean;
  user: User | null;
  onReturn: () => void;
  onRequest: () => void;
  onSignOut: () => Promise<void>;
  onComplete: () => void;
}) {
  const [password,setPassword]=useState('');
  const [confirmation,setConfirmation]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const revision=useRef(0);
  useEffect(() => {
    revision.current++;setPassword('');setConfirmation('');setError('');setBusy(false);
    return () => { revision.current++; };
  },[token,user?.id]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if(!token || user || completed || busy) return;
    const issue=passwordError(password);
    if(issue) {setError(issue);return;}
    if(password!==confirmation) {setError('The new passwords must match.');return;}
    const current=revision.current;
    setBusy(true);setError('');
    try {
      await passwordResetApi.confirmReset(token,password);
      if(current===revision.current) {setPassword('');setConfirmation('');onComplete();}
    } catch(error) {
      if(current===revision.current) setError(resetErrorMessage(error instanceof Error ? error : {}));
    } finally {
      if(current===revision.current) {setPassword('');setConfirmation('');setBusy(false);}
    }
  }
  async function signOut() {
    if(busy) return;
    const current=revision.current;
    setBusy(true);setError('');
    try { await onSignOut(); }
    catch(error) { if(current===revision.current) setError(error instanceof Error ? error.message : 'Unable to sign out. Please try again.'); }
    finally { if(current===revision.current) setBusy(false); }
  }
  return <div className="auth-shell verification-shell"><div className="auth-appearance"><ThemeSelect/></div><section className="auth-card" aria-labelledby="reset-password-title">
    <LockKeyhole className="verification-icon" size={28} aria-hidden="true"/>
    <h2 id="reset-password-title">{completed ? 'Password reset.' : 'Choose a new password.'}</h2>
    {completed ? <><p role="status">Sign in with your new password to open your workspace.</p><p className="field-note">If your account still needs email verification, open your verification link and use your new password. You can request a verification email from sign in.</p></> : !token ? <div className="error-box" role="alert">This reset link is incomplete or invalid. Reopen the email or request a new link.</div> : user ? <><p>You are currently signed in as <strong>{user.email}</strong>. Sign out before choosing a new password with this recovery link.</p><button className="button secondary" disabled={busy} onClick={signOut}>{busy ? 'Signing out…' : 'Sign out to reset a password'}</button></> : <>
      <p>Opening this link does not change your password. Submit a new password when you are ready.</p>
      <form className="application-form auth-form" onSubmit={submit}>
        <label>New password<input type="password" required autoComplete="new-password" aria-describedby="reset-password-policy" value={password} onChange={event => setPassword(event.target.value)} disabled={busy}/></label>
        <label>Confirm new password<input type="password" required autoComplete="new-password" value={confirmation} onChange={event => setConfirmation(event.target.value)} disabled={busy}/></label>
        <small className="field-note" id="reset-password-policy">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes; some characters use more than one byte. The server checks common and compromised passwords.</small>
        <button type="submit" className="button primary" disabled={busy}>{busy ? 'Resetting…' : 'Reset password'}</button>
      </form>
      <p className="field-note verification-note">After a page refresh, reopen the link from your email. If a response is lost, try signing in with your new password before requesting another link.</p>
    </>}
    {error && <div className="error-box" role="alert">{error}</div>}
    <div className="verification-actions">
      <button className="text-button" disabled={busy} onClick={onReturn}>{user ? 'Return to current workspace' : 'Back to sign in'}</button>
      {!completed && !user && <button className="text-button" disabled={busy} onClick={onRequest}>Request a new reset link</button>}
    </div>
  </section></div>;
}
