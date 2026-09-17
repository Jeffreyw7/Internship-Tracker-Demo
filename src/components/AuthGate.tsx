import { useEffect, useState, type FormEvent } from 'react';
import { GraduationCap, LockKeyhole, ArrowRight } from 'lucide-react';
import { ApiError, authApi, type User } from '../data/api';
import App from '../App';
import { PASSWORD_REQUIREMENTS, passwordError } from '../domain/password';

export default function AuthGate() {
  const [user,setUser]=useState<User|null>(null);
  const [loading,setLoading]=useState(true);
  const [mode,setMode]=useState<'login'|'register'>('login');
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const [displayName,setDisplayName]=useState('');
  const [email,setEmail]=useState('');
  const [password,setPassword]=useState('');
  const [samples,setSamples]=useState(true);
  useEffect(()=>{
    let alive=true;
    authApi.me().then(u=>{if(alive)setUser(u);}).catch(e=>{if(alive&&!(e instanceof ApiError&&e.status===401))setError(e.message);}).finally(()=>{if(alive)setLoading(false);});
    const expired=()=>{setUser(null);setError('Your session ended. Sign in to continue.');};
    window.addEventListener('campus:session-expired',expired);
    return()=>{alive=false;window.removeEventListener('campus:session-expired',expired);};
  },[]);
  async function submit(e:FormEvent) {
    e.preventDefault();if(busy)return;
    if(mode==='register'){const issue=passwordError(password);if(issue){setError(issue);return;}}
    setBusy(true);setError('');
    try {const u=mode==='register'?await authApi.register(displayName,email,password,samples):await authApi.login(email,password);setUser(u);setPassword('');}
    catch(e){setError(e instanceof Error?e.message:'Unable to sign in.');}finally{setBusy(false);}
  }
  async function logout(){await authApi.logout();setUser(null);setError('');}
  if(loading)return <div className="auth-shell"><div className="auth-card" role="status">Opening your workspace…</div></div>;
  if(user)return <App key={user.id} user={user} onLogout={logout}/>;
  return <div className="auth-shell"><div className="auth-story"><div className="brand"><span className="brand-symbol"><GraduationCap size={24}/></span><span>campus.</span></div><span className="eyebrow">YOUR NEXT CHAPTER</span><h1>Make room for<br/>what comes next.</h1><p>Your applications, interviews, and next steps.<br/>One personal workspace, ready when you are.</p><span className="auth-note"><LockKeyhole size={15}/> Your applications are private to your account.</span></div><div className="auth-card"><h2>{mode==='login'?'Welcome back.':'Start your next chapter.'}</h2><p>{mode==='login'?'Sign in to your internship workspace.':'Create your account and make your first move.'}</p><form className="application-form auth-form" onSubmit={submit}>
    {mode==='register'&&<label>Your name<input required maxLength={80} autoComplete="name" value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Jeffrey Wang"/></label>}
    <label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@example.com"/></label>
    <label>Password<input type="password" required minLength={mode==='register'?12:1} maxLength={72} aria-describedby={mode==='register'?'password-requirements':undefined} autoComplete={mode==='register'?'new-password':'current-password'} value={password} onChange={e=>setPassword(e.target.value)}/></label>
    {mode==='register'&&<><small id="password-requirements" className="field-note">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes; some characters use more than one byte.</small><label className="sample-check"><input type="checkbox" checked={samples} onChange={e=>setSamples(e.target.checked)}/>Start with 10 illustrative sample applications</label></>}
    {error&&<div className="error-box" role="alert">{error}</div>}
    <button disabled={busy} className="button primary" type="submit">{busy?'Please wait…':mode==='login'?'Sign in':'Create workspace'}<ArrowRight size={16}/></button>
  </form><button className="text-button auth-switch" disabled={busy} onClick={()=>{setMode(mode==='login'?'register':'login');setError('');}}>{mode==='login'?'New here? Create an account':'Already have an account? Sign in'}</button></div></div>;
}
