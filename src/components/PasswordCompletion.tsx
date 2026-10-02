import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, passwordApi } from '../data/api';
import type { PasswordLink } from '../domain/passwordLink';
import { passwordError, PASSWORD_REQUIREMENTS } from '../domain/password';
export default function PasswordCompletion({ link, signedIn, onComplete, onReturn }: {
  link: PasswordLink; signedIn: boolean; onComplete: (message: string) => void; onReturn: () => void;
}) {
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [invalid, setInvalid] = useState(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const unavailable = !link.token || invalid || (link.purpose === 'change' && !signedIn);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy || unavailable || uncertain || !link.token) return;
    const issue = passwordError(password);
    if (issue || password !== confirmation) { setError(issue ?? 'Passwords must match.'); return; }
    setBusy(true); setError('');
    try {
      const result = await (link.purpose === 'reset' ? passwordApi.confirmReset(link.token, password) : passwordApi.confirmChange(link.token, password));
      if (alive.current) onComplete(result.message);
    } catch (e) {
      if (!alive.current) return;
      if (e instanceof ApiError && e.code === 'PASSWORD_CHALLENGE_INVALID') setInvalid(true);
      if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) {
        setUncertain(true); setError('We could not confirm the result. Try signing in with your new password. If that fails, request a new reset email.');
      } else setError(e.message);
    } finally { if (alive.current) { setBusy(false); setPassword(''); setConfirmation(''); } }
  }
  return <div className="auth-shell password-shell"><section className="auth-card" aria-labelledby="completion-title">
    <h2 id="completion-title">Set your new password.</h2>
    <p>{link.purpose === 'reset' ? 'This resets the account that received the email. All of its signed-in sessions will end.' : 'Your current password and this email link authorize the change. All signed-in sessions will end.'}</p>
    {unavailable ? <p role="alert">This link is invalid, expired, or requires the browser where you requested the change. Return to sign in and request a new email.</p> :
      <form className="application-form auth-form" onSubmit={submit}>
        <label>New password<input type="password" autoComplete="new-password" required maxLength={72} aria-describedby="new-password-rules" value={password} onChange={e => setPassword(e.target.value)} disabled={busy || uncertain} /></label>
        <small id="new-password-rules" className="field-note">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes.</small>
        <label>Confirm new password<input type="password" autoComplete="new-password" required maxLength={72} value={confirmation} onChange={e => setConfirmation(e.target.value)} disabled={busy || uncertain} /></label>
        <button className="button primary" disabled={busy || uncertain}>{busy ? 'Saving…' : 'Save new password'}</button>
      </form>}
    {error && <div className="error-box" role="alert">{error}</div>}
    <button className="text-button auth-switch" disabled={busy} onClick={onReturn}>Return to sign in</button>
  </section></div>;
}
