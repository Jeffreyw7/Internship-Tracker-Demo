// Checks the session and switches between account forms and the signed-in workspace.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { GraduationCap, LockKeyhole, ArrowRight } from 'lucide-react';
import { ApiError, authApi, type User } from '../data/api';
import App from '../App';
import { PASSWORD_REQUIREMENTS, passwordError } from '../domain/password';
import { initialPasswordLink, clearInitialPasswordLink, readPasswordLink, type PasswordLink } from '../domain/passwordLink';
import PasswordRecovery from './PasswordRecovery';
import PasswordCompletion from './PasswordCompletion';
import AccountSecurity from './AccountSecurity';

export default function AuthGate() {
  // Track authentication, form input, and request state independently.
  const [user,setUser]=useState<User|null>(null);
  const [loading,setLoading]=useState(true);
  const [mode,setMode]=useState<'login'|'register'>('login');
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const [displayName,setDisplayName]=useState('');
  const [email,setEmail]=useState('');
  const [password,setPassword]=useState('');
  const [samples,setSamples]=useState(true);
  const [link,setLink]=useState<PasswordLink|null>(initialPasswordLink);
  const [screen,setScreen]=useState<'auth'|'recovery'|'security'>('auth');
  const [notice,setNotice]=useState('');
  const epoch=useRef(0);
  // Load the current profile and listen for session-expiration events from the API layer.
  useEffect(()=>{
    let alive=true;const current=epoch.current;
    authApi.me().then(u=>{if(alive&&epoch.current===current)setUser(u);}).catch(e=>{if(alive&&!(e instanceof ApiError&&e.status===401))setError(e.message);}).finally(()=>{if(alive)setLoading(false);});
    const expired=()=>{epoch.current++;setUser(null);setScreen('auth');setBusy(false);setPassword('');setError('Your session ended. Sign in to continue.');};
    const changedLink=()=>{const next=readPasswordLink(window.location,window.history);if(next){epoch.current++;clearInitialPasswordLink();setBusy(false);setLink(next);setPassword('');}};
    window.addEventListener('campus:session-expired',expired);
    window.addEventListener('hashchange',changedLink);
    return()=>{alive=false;epoch.current++;window.removeEventListener('campus:session-expired',expired);window.removeEventListener('hashchange',changedLink);};
  },[]);
  // Validate new passwords locally, perform the selected auth action, and clear credentials on success.
  async function submit(e:FormEvent) {
    e.preventDefault();if(busy)return;
    if(mode==='register'){const issue=passwordError(password);if(issue){setError(issue);return;}}
    const current=epoch.current;setBusy(true);setError('');setNotice('');
    try {const u=mode==='register'?await authApi.register(displayName,email,password,samples):await authApi.login(email,password);if(epoch.current===current){setUser(u);setPassword('');}}
    catch(e){if(epoch.current===current)setError(e instanceof Error?e.message:'Unable to sign in.');}finally{if(epoch.current===current)setBusy(false);}
  }
  // Return to the account screen only after the server confirms logout.
  async function logout(){await authApi.logout();epoch.current++;setUser(null);setScreen('auth');setPassword('');setError('');}
  function returnToAuth(){epoch.current++;clearInitialPasswordLink();setBusy(false);setLink(null);setScreen('auth');setMode('login');setPassword('');setError('');}
  function completed(message:string){returnToAuth();setUser(null);setNotice(message);}
  // Avoid showing the sign-in screen until the initial session lookup finishes.
  if(loading)return <div className="auth-shell"><div className="auth-card" role="status">Opening your workspace…</div></div>;
  if(link)return <PasswordCompletion key={link.purpose+link.token} link={link} signedIn={!!user} onComplete={completed} onReturn={returnToAuth}/>;
  if(screen==='recovery')return <PasswordRecovery onReturn={returnToAuth}/>;
  if(user&&screen==='security')return <AccountSecurity onReturn={()=>setScreen('auth')} onRecovery={()=>setScreen('recovery')}/>;
  if(user)return <App key={user.id} user={user} onLogout={logout} onSecurity={()=>setScreen('security')}/>;
  // Render signup-specific fields and policy guidance only in registration mode.
  return <div className="auth-shell"><div className="auth-story"><div className="brand"><span className="brand-symbol"><GraduationCap size={24}/></span><span>campus.</span></div><span className="eyebrow">YOUR NEXT CHAPTER</span><h1>Make room for<br/>what comes next.</h1><p>Your applications, interviews, and next steps.<br/>One personal workspace, ready when you are.</p><span className="auth-note"><LockKeyhole size={15}/> Your applications are private to your account.</span></div><div className="auth-card"><h2>{mode==='login'?'Welcome back.':'Start your next chapter.'}</h2><p>{mode==='login'?'Sign in to your internship workspace.':'Create your account and make your first move.'}</p><form className="application-form auth-form" onSubmit={submit}>
    {/* Collect a display name only when creating a new account. */}
    {mode==='register'&&<label>Your name<input required maxLength={80} autoComplete="name" value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Jeffrey Wang"/></label>}
    {/* Use email as the account identifier for both signup and login. */}
    <label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@example.com"/></label>
    {/* Use the appropriate browser autofill mode and associate signup requirements with the input. */}
    <label>Password<input type="password" required minLength={mode==='register'?12:1} maxLength={72} aria-describedby={mode==='register'?'password-requirements':undefined} autoComplete={mode==='register'?'new-password':'current-password'} value={password} onChange={e=>setPassword(e.target.value)}/></label>
    {/* Explain the enforced password policy and let the user opt into sample records. */}
    {mode==='register'&&<><small id="password-requirements" className="field-note">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes; some characters use more than one byte.</small><label className="sample-check"><input type="checkbox" checked={samples} onChange={e=>setSamples(e.target.checked)}/>Start with 10 illustrative sample applications</label></>}
    {/* Announce validation and server errors to assistive technology. */}
    {notice&&<p role="status">{notice}</p>}
    {error&&<div className="error-box" role="alert">{error}</div>}
    <button disabled={busy} className="button primary" type="submit">{busy?'Please wait…':mode==='login'?'Sign in':'Create workspace'}<ArrowRight size={16}/></button>
  </form><button className="text-button auth-switch" disabled={busy} onClick={()=>{setMode(mode==='login'?'register':'login');setPassword('');setError('');}}>{mode==='login'?'New here? Create an account':'Already have an account? Sign in'}</button>{mode==='login'&&<button className="text-button auth-switch" disabled={busy} onClick={()=>{setPassword('');setScreen('recovery');}}>Forgot password?</button>}</div></div>;
}
