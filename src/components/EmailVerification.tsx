import { useState, type FormEvent } from 'react';
import { ApiError, authApi, type User } from '../data/api';
import AuthLayout from './AuthLayout';
import PasswordField from './PasswordField';

function ResendVerification({ initialEmail }: { initialEmail: string }) {
  const [email, setEmail] = useState(initialEmail);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  async function resend(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const result = await authApi.resendVerification(email);
      setMessage(result.message);
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Unable to request an email. Please try again.');
    } finally { setBusy(false); }
  }
  return <form className="account-form account-resend" onSubmit={resend}>
    <label>Email for a new verification link<input type="email" required maxLength={254} autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} disabled={busy}/></label>
    <p className="account-hint">If this address has an account awaiting verification, we will send a link when eligible. Check spam too; delivery may take a few minutes.</p>
    {error && <div className="account-error" role="alert">{error}</div>}
    {message && <div className="account-status" role="status">{message}</div>}
    <button type="submit" className="account-submit account-secondary" disabled={busy}>{busy ? 'Requesting…' : 'Resend verification email'}</button>
  </form>;
}

export function CheckEmail({ email, onSignIn, onHome }: { email: string; onSignIn: () => void; onHome: () => void }) {
  return <AuthLayout title="Check your email." verification onHome={onHome}>
    <p className="account-message" role="status">If your account is awaiting verification, open the link in your email and enter the password you chose at signup. Then sign in to your workspace.</p>
    <ResendVerification initialEmail={email}/>
    <div className="account-verification-actions"><button className="account-link" onClick={onSignIn}>Back to sign in</button></div>
  </AuthLayout>;
}

export default function EmailVerification({ token, user, onReturn, onHome, onSwitchAccount }: {
  token: string | null;
  user: User | null;
  onReturn: () => void;
  onHome: () => void;
  onSwitchAccount: () => Promise<void>;
}) {
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState(token ? '' : 'This verification link is incomplete or invalid. Reopen the email or request a new link.');
  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (!token || busy) return;
    setBusy(true); setError('');
    try {
      await authApi.confirmVerification(token, password);
      setVerified(true);
    } catch (error) {
      setError(error instanceof ApiError && error.code === 'VERIFICATION_INVALID'
        ? 'We could not verify this email. Check your signup password; the link may have expired or already been used. You can try signing in or request a new link.'
        : error instanceof Error ? error.message : 'Unable to confirm your email. Please try again.');
    } finally { setPassword(''); setBusy(false); }
  }
  async function switchAccount() {
    if (busy) return;
    setBusy(true); setError('');
    try { await onSwitchAccount(); }
    catch (error) { setError(error instanceof Error ? error.message : 'Unable to sign out. Please try again.'); }
    finally { setBusy(false); }
  }
  return <AuthLayout title={verified ? 'Email verified.' : 'Verify your email.'} verification onHome={onHome}>
    {verified ? <p className="account-message" role="status">Your email is verified. Sign in with your email and password to open your workspace.</p> : <p className="account-message">Enter the password you chose when creating the account associated with this email link. Opening the link alone does not verify your account.</p>}
    {user && <p className="account-status">You are currently signed in as <strong>{user.email}</strong>. Verification keeps your current account signed in.</p>}
    {error && <div className="account-error" role="alert">{error}</div>}
    {!verified && token && <form className="account-form" onSubmit={confirm}>
      <PasswordField label="Signup password" value={password} onChange={setPassword} autoComplete="current-password" disabled={busy}/>
      <button type="submit" className="account-submit" disabled={busy}>{busy ? 'Verifying…' : 'Verify email'}</button>
    </form>}
    {!verified && <><p className="account-message">If you refresh this page, reopen the link from your email. If confirmation appeared to fail, try signing in before requesting another link.</p><ResendVerification initialEmail=""/></>}
    <div className="account-verification-actions">
      <button className="account-link" disabled={busy} onClick={onReturn}>{user ? 'Return to current workspace' : 'Back to sign in'}</button>
      {user && <button className="account-link" disabled={busy} onClick={switchAccount}>Sign out to use another account</button>}
    </div>
  </AuthLayout>;
}
