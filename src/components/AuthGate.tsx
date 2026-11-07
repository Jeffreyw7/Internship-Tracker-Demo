// Checks the session and switches between account forms and the signed-in workspace.
import { useEffect, useLayoutEffect, useState, useSyncExternalStore, type FormEvent } from 'react';
import { ArrowRight } from 'lucide-react';
import { ApiError, authApi, subscribeSessionChanges, type User } from '../data/api';
import App from '../App';
import { PASSWORD_REQUIREMENTS, passwordError } from '../domain/password';
import { useTheme } from '../theme/ThemeProvider';
import AuthLayout from './AuthLayout';
import PasswordField from './PasswordField';
import EmailVerification, { CheckEmail } from './EmailVerification';
import { verificationLinks } from '../domain/verificationLink';
import LandingPage from './LandingPage';

const emptySubscribe = () => () => {};
const emptySnapshot = () => null;
const publicScreen = () => window.location.hash === '#create-account' ? 'register' : window.location.hash === '#sign-in' ? 'login' : 'landing';
const subscribeNavigation = (listener: () => void) => {
  window.addEventListener('hashchange', listener);
  return () => window.removeEventListener('hashchange', listener);
};
function openAuth(mode: 'login' | 'register') {
  // Native hash navigation preserves back/forward without requiring server-side routes.
  window.location.hash = mode === 'register' ? 'create-account' : 'sign-in';
}

export default function AuthGate() {
  // Track authentication, form input, and request state independently.
  const [user,setUser]=useState<User|null>(null);
  const [loading,setLoading]=useState(true);
  const screen=useSyncExternalStore(subscribeNavigation,publicScreen,()=> 'landing');
  const mode=screen==='register'?'register':'login';
  const [checkEmail,setCheckEmail]=useState(false);
  const verification=useSyncExternalStore(verificationLinks?.subscribe??emptySubscribe,verificationLinks?.getSnapshot??emptySnapshot,emptySnapshot);
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const {bindAccount}=useTheme();
  useLayoutEffect(()=>{
    bindAccount(loading?undefined:user?.id??null);
    return ()=>bindAccount(undefined);
  },[bindAccount,loading,user?.id]);
  const [displayName,setDisplayName]=useState('');
  const [email,setEmail]=useState('');
  const [password,setPassword]=useState('');
  const [samples,setSamples]=useState(true);
  useEffect(()=>{setPassword('');setCheckEmail(false);if(screen!=='landing')window.scrollTo(0,0);},[screen]);
  // Load the current profile and listen for session-expiration events from the API layer.
  useEffect(()=>{
    let alive=true,revision=0;
    const loadProfile=()=>{
      const current=++revision;
      setUser(null);setLoading(true);setPassword('');setError('');
      authApi.me().then(u=>{if(alive&&current===revision)setUser(u);})
        .catch(e=>{if(alive&&current===revision&&!(e instanceof ApiError&&e.status===401))setError(e.message);})
        .finally(()=>{if(alive&&current===revision)setLoading(false);});
    };
    loadProfile();
    const unsubscribe=subscribeSessionChanges(loadProfile);
    const expired=()=>{revision++;setUser(null);setLoading(false);openAuth('login');setError('Your session ended. Sign in to continue.');};
    window.addEventListener('campus:session-expired',expired);
    return()=>{alive=false;unsubscribe();window.removeEventListener('campus:session-expired',expired);};
  },[]);
  // Validate new passwords locally, perform the selected auth action, and clear credentials on success.
  async function submit(e:FormEvent) {
    e.preventDefault();if(busy)return;
    if(mode==='register'){const issue=passwordError(password);if(issue){setError(issue);return;}}
    setBusy(true);setError('');
    try {
      if(mode==='register'){await authApi.register(displayName,email,password,samples);setCheckEmail(true);}
      else setUser(await authApi.login(email,password));
      setPassword('');
    }
    catch(e){
      if(e instanceof ApiError&&e.code==='EMAIL_VERIFICATION_REQUIRED'){setCheckEmail(true);setPassword('');}
      else setError(e instanceof Error?e.message:'Unable to sign in.');
    }finally{setBusy(false);}
  }
  // Return to the account screen only after the server confirms logout.
  async function logout(){await authApi.logout();setUser(null);setError('');openAuth('login');}
  function returnToSignIn(){verificationLinks?.dismiss();setCheckEmail(false);openAuth('login');setPassword('');setError('');}
  function returnHome(){verificationLinks?.dismiss();setCheckEmail(false);setPassword('');setError('');window.location.hash='top';}
  // Avoid showing account forms until the initial session lookup finishes.
  if(loading)return <div className="account-page account-loading" role="status">Opening your workspace.</div>;
  if(verification)return <EmailVerification key={verification.token??'invalid'} token={verification.token} user={user} onReturn={returnToSignIn} onHome={returnHome} onSwitchAccount={async()=>{await logout();returnToSignIn();}}/>;
  if(user)return <App key={user.id} user={user} onLogout={logout}/>;
  if(checkEmail)return <CheckEmail email={email} onSignIn={returnToSignIn} onHome={returnHome}/>;
  if(screen==='landing')return <LandingPage/>;
  return <AuthLayout title={mode==='login'?'Welcome back.':'Start your next chapter.'}
    description={mode==='login'?'Sign in to your internship workspace.':'Create your account and make your first move.'} onHome={returnHome}>
    <form className="account-form" onSubmit={submit} aria-busy={busy}>
      {mode==='register'&&<label>Your name<input name="displayName" required maxLength={80} autoComplete="name" disabled={busy} value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Your name"/></label>}
      <label>Email<input name="email" type="email" required maxLength={254} autoComplete="username" disabled={busy} value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@example.com"/></label>
      <PasswordField key={mode} value={password} onChange={setPassword} disabled={busy}
        minLength={mode==='register'?12:1} autoComplete={mode==='register'?'new-password':'current-password'}
        describedBy={mode==='register'?'password-requirements':undefined}/>
      {mode==='register'&&<>
        <small id="password-requirements" className="account-hint">{PASSWORD_REQUIREMENTS} Maximum 72 UTF-8 bytes; some characters use more than one byte.</small>
        <label className="account-samples"><input type="checkbox" checked={samples} disabled={busy} onChange={e=>setSamples(e.target.checked)}/><span>Start with 10 illustrative sample applications<small>Explore the workspace, then make it your own.</small></span></label>
      </>}
      {error&&<div className="account-error" role="alert">{error}</div>}
      <button disabled={busy} className="account-submit" type="submit">{busy?'Please wait.':mode==='login'?'Sign in':'Create workspace'}<ArrowRight size={18} aria-hidden="true"/></button>
    </form>
    <p className="account-switch">{mode==='login'?'New here?':'Already have an account?'}{' '}
      <button className="account-link" disabled={busy} onClick={()=>{openAuth(mode==='login'?'register':'login');setPassword('');setError('');}}>{mode==='login'?'Create an account':'Sign in'}</button>
    </p>
    {mode==='login'&&<div className="account-help"><button className="account-link" disabled={busy} onClick={()=>{setCheckEmail(true);setPassword('');setError('');}}>Need a verification email?</button></div>}
    {mode==='register'&&<p className="account-next-step">Next, confirm your email to open your workspace.</p>}
  </AuthLayout>;
}
