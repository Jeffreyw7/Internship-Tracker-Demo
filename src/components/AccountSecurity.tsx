import { useEffect, useRef, useState, type FormEvent } from 'react';
import { passwordApi } from '../data/api';
export default function AccountSecurity({ onReturn, onRecovery }: { onReturn: () => void; onRecovery: () => void }) {
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError('');
    try { const result = await passwordApi.requestChange(password); if (alive.current) { setMessage(result.message); setPassword(''); } }
    catch (e) { if (alive.current) setError(e instanceof Error ? e.message : 'Unable to request a password change.'); }
    finally { if (alive.current) { setBusy(false); setPassword(''); } }
  }
  return <div className="auth-shell password-shell"><section className="auth-card" aria-labelledby="security-title">
    <h2 id="security-title">Change your password.</h2><p>Confirm your current password, then open the link sent to your account email in this browser within 15 minutes.</p>
    <form className="application-form auth-form" onSubmit={submit}>
      <label>Current password<input type="password" autoComplete="current-password" required maxLength={256} value={password} onChange={e => setPassword(e.target.value)} /></label>
      {message && <p role="status">{message}</p>}{error && <div className="error-box" role="alert">{error}</div>}
      <button className="button primary" disabled={busy}>{busy ? 'Sending…' : 'Send verification email'}</button>
    </form><button className="text-button auth-switch" disabled={busy} onClick={onRecovery}>Forgot your current password?</button>
    <button className="text-button auth-switch" disabled={busy} onClick={onReturn}>Back to workspace</button>
  </section></div>;
}
