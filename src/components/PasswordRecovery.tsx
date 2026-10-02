import { useEffect, useRef, useState, type FormEvent } from 'react';
import { passwordApi } from '../data/api';
export default function PasswordRecovery({ onReturn }: { onReturn: () => void }) {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError('');
    try { const result = await passwordApi.requestReset(email); if (alive.current) setMessage(result.message); }
    catch (e) { if (alive.current) setError(e instanceof Error ? e.message : 'Unable to request an email.'); }
    finally { if (alive.current) setBusy(false); }
  }
  return <div className="auth-shell password-shell"><section className="auth-card" aria-labelledby="recovery-title">
    <h2 id="recovery-title">Recover your access.</h2><p>Enter your account email to receive a password reset link.</p>
    <form className="application-form auth-form" onSubmit={submit}>
      <label>Email<input type="email" autoComplete="username" required maxLength={254} value={email} onChange={e => setEmail(e.target.value)} /></label>
      {message && <p role="status">{message} Links expire after 30 minutes. Check your spam folder too.</p>}
      {error && <div className="error-box" role="alert">{error}</div>}
      <button className="button primary" disabled={busy}>{busy ? 'Sending…' : message ? 'Send another email' : 'Send reset email'}</button>
    </form><button className="text-button auth-switch" disabled={busy} onClick={onReturn}>Back to sign in</button>
  </section></div>;
}
