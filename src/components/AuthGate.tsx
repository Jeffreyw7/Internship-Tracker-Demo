// Checks the session and switches between account forms and the signed-in workspace.
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react';
import { GraduationCap, LockKeyhole, ArrowRight } from 'lucide-react';
import { ApiError, authApi, subscribeSessionChanges, type User } from '../data/api';
import App from '../App';
import { PASSWORD_REQUIREMENTS, passwordError } from '../domain/password';
import { useTheme } from '../theme/ThemeProvider';
import { ThemeSelect } from './ThemeSelect';
import EmailVerification, { CheckEmail } from './EmailVerification';
import { verificationLinks } from '../domain/verificationLink';
import { passwordResetLinks } from '../domain/passwordResetLink';
import ForgotPassword from './ForgotPassword';
import ResetPassword from './ResetPassword';

const emptySubscribe = () => () => {};
const emptySnapshot = () => null;

export default function AuthGate() {
  // Track authentication, form input, and request state independently.
  const [user,setUser]=useState<User|null>(null);
  const [loading,setLoading]=useState(true);
  const [profileRetry,setProfileRetry]=useState(0);
  const [mode,setMode]=useState<'login'|'register'>('login');
  const [checkEmail,setCheckEmail]=useState(false);
  const [forgotPassword,setForgotPassword]=useState(false);
  const verification=useSyncExternalStore(verificationLinks?.subscribe??emptySubscribe,verificationLinks?.getSnapshot??emptySnapshot,emptySnapshot);
  const reset=useSyncExternalStore(passwordResetLinks?.subscribe??emptySubscribe,passwordResetLinks?.getSnapshot??emptySnapshot,emptySnapshot);
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const revision=useRef(0);
  const transitioning=useRef(false);
  const {bindAccount}=useTheme();
  useLayoutEffect(()=>{
    bindAccount(loading?undefined:user?.id??null);
    return ()=>bindAccount(undefined);
  },[bindAccount,loading,user?.id]);
  const [displayName,setDisplayName]=useState('');
  const [email,setEmail]=useState('');
  const [password,setPassword]=useState('');
  const [samples,setSamples]=useState(true);
  // Load the current profile and listen for session-expiration events from the API layer.
  useEffect(()=>{
    let alive=true;
    function loadProfile() {
      const current=++revision.current;
      // The profile adapter invalidates old account work when it binds a new identity.
      const active=()=>alive && current===revision.current;
      authApi.me().then(u=>{if(active()){setUser(u);setLoading(false);}}).catch(e=>{
        if(!active())return;
        // Only an explicit unauthenticated response establishes an anonymous session.
        // A transient failure must preserve a known account and keep initial recovery gated.
        if(e instanceof ApiError&&e.status===401){setUser(null);setLoading(false);}
        else setError(e instanceof Error?e.message:'Unable to check your session.');
      });
    }
    loadProfile();
    const expired=()=>{revision.current++;transitioning.current=false;setBusy(false);setLoading(false);setUser(null);setPassword('');setError('Your session ended. Sign in to continue.');};
    const changed=()=>{setUser(null);setPassword('');setBusy(false);transitioning.current=false;setLoading(true);setError('');loadProfile();};
    // A focus refresh must preserve recovery input and pending submissions until the account actually changes.
    const refresh=()=>{if(!transitioning.current)loadProfile();};
    const unsubscribe=subscribeSessionChanges(changed);
    window.addEventListener('campus:session-expired',expired);
    window.addEventListener('focus',refresh);
    return()=>{alive=false;revision.current++;unsubscribe();window.removeEventListener('campus:session-expired',expired);window.removeEventListener('focus',refresh);};
  },[profileRetry]);
  // Validate new passwords locally, perform the selected auth action, and clear credentials on success.
  async function submit(e:FormEvent) {
    e.preventDefault();if(busy)return;
    if(mode==='register'){const issue=passwordError(password);if(issue){setError(issue);return;}}
    const current=++revision.current;
    transitioning.current=true;
    setBusy(true);setError('');
    try {
      if(mode==='register'){await authApi.register(displayName,email,password,samples);if(current===revision.current)setCheckEmail(true);}
      else {const profile=await authApi.login(email,password);if(current===revision.current)setUser(profile);}
      if(current===revision.current)setPassword('');
    }
    catch(e){
      if(current!==revision.current)return;
      if(e instanceof ApiError&&e.code==='EMAIL_VERIFICATION_REQUIRED'){setCheckEmail(true);setPassword('');}
      else setError(e instanceof Error?e.message:'Unable to sign in.');
    }finally{if(current===revision.current){setBusy(false);transitioning.current=false;}}
  }
  // Return to the account screen only after the server confirms logout.
  async function logout(){
    const current=++revision.current;transitioning.current=true;
    try {
      await authApi.logout();
      if(current!==revision.current)throw new ApiError(409,'Your session changed. Please try again.',undefined,'AUTH_SESSION_CHANGED');
      setUser(null);setPassword('');setError('');
    } finally {if(current===revision.current)transitioning.current=false;}
  }
  function returnToSignIn(){verificationLinks?.dismiss();passwordResetLinks?.dismiss();setForgotPassword(false);setCheckEmail(false);setMode('login');setPassword('');setError('');}
  // Avoid showing the sign-in screen until the initial session lookup finishes.
  if(loading)return <div className="auth-shell"><div className="auth-appearance"><ThemeSelect/></div><div className="auth-card">{error?<><div className="error-box" role="alert">{error}</div><button className="button primary" onClick={()=>{setError('');setProfileRetry(value=>value+1);}}>Retry session check</button></>:<div role="status">Opening your workspace…</div>}</div></div>;
  if(reset)return <ResetPassword token={reset.token} completed={reset.completed??false} user={user} onReturn={returnToSignIn} onRequest={()=>{returnToSignIn();setForgotPassword(true);}} onSignOut={logout} onComplete={()=>{passwordResetLinks?.complete();setPassword('');}}/>;
  if(verification)return <EmailVerification key={verification.token??'invalid'} token={verification.token} user={user} onReturn={returnToSignIn} onSwitchAccount={async()=>{await logout();returnToSignIn();}}/>;
  if(user)return <App key={user.id} user={user} onLogout={logout}/>;
  if(forgotPassword)return <ForgotPassword initialEmail={email} onReturn={returnToSignIn}/>;
  if(checkEmail)return <CheckEmail email={email} onSignIn={returnToSignIn}/>;
  // Render signup-specific fields and policy guidance only in registration mode.
  return <div className="auth-shell"><div className="auth-appearance"><ThemeSelect/></div><div className="auth-story"><div className="brand"><span className="brand-symbol"><GraduationCap size={24}/></span><span>campus.</span></div><span className="eyebrow">YOUR NEXT CHAPTER</span><h1>Make room for<br/>what comes next.</h1><p>Your applications, interviews, and next steps.<br/>One personal workspace, ready when you are.</p><span className="auth-note"><LockKeyhole size={15}/> Your applications are private to your account.</span></div><div className="auth-card"><h2>{mode==='login'?'Welcome back.':'Start your next chapter.'}</h2><p>{mode==='login'?'Sign in to your internship workspace.':'Create your account and make your first move.'}</p><form className="application-form auth-form" onSubmit={submit}>
    {/* Collect a display name only when creating a new account. */}
    {mode==='register'&&<label>Your name<input required maxLength={80} autoComplete="name" value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Jeffrey Wang"/></label>}
    {/* Use email as the account identifier for both signup and login. */}
    <label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@example.com"/></label>
    {/* Use the appropriate browser autofill mode and associate signup requirements with the input. */}
    <label>Password<input type="password" required aria-describedby={mode==='register'?'password-requirements':undefined} autoComplete={mode==='register'?'new-password':'current-password'} value={password} onChange={e=>setPassword(e.target.value)}/></label>
    {/* Explain the enforced password policy and let the user opt into sample records. */}
    {mode==='register'&&<><small id="password-requirements" className="field-note">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes; some characters use more than one byte.</small><label className="sample-check"><input type="checkbox" checked={samples} onChange={e=>setSamples(e.target.checked)}/>Start with 10 illustrative sample applications</label></>}
    {/* Announce validation and server errors to assistive technology. */}
    {error&&<div className="error-box" role="alert">{error}</div>}
    <button disabled={busy} className="button primary" type="submit">{busy?'Please wait…':mode==='login'?'Sign in':'Create workspace'}<ArrowRight size={16}/></button>
  </form><button className="text-button auth-switch" disabled={busy} onClick={()=>{setMode(mode==='login'?'register':'login');setPassword('');setError('');}}>{mode==='login'?'New here? Create an account':'Already have an account? Sign in'}</button>{mode==='login'&&<><button className="text-button auth-switch" disabled={busy} onClick={()=>{setForgotPassword(true);setPassword('');setError('');}}>Forgot password?</button><button className="text-button auth-switch" disabled={busy} onClick={()=>{setCheckEmail(true);setPassword('');setError('');}}>Need a verification email?</button></>}</div></div>;
}
