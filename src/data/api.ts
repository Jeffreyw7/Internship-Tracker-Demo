// Retain the HTTP status so callers can distinguish conflicts and expired sessions.
// Centralizes same-origin API requests, session handling, CSRF tokens, and user-facing errors.
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfterSeconds?: number;
  readonly details?: {rowNumber:number;field:string;message:string}[];
  readonly authoritative:boolean;
  constructor(status: number, message: string, retryAfterSeconds?: number, code?: string, details?:{rowNumber:number;field:string;message:string}[], authoritative=false) {
    super(message); this.name='ApiError'; this.status=status; this.retryAfterSeconds=retryAfterSeconds; this.code=code;this.details=details;this.authoritative=authoritative;
  }
}
async function responseError(response:Response, fallback:string):Promise<ApiError> {
  const data:unknown=await response.json().catch(()=>null);
  const message=data && typeof data==='object' && 'message' in data && typeof data.message==='string'
    ? data.message : fallback;
  const raw=response.headers.get('Retry-After')?.trim();
  let retryAfterSeconds:number|undefined;
  if(raw && /^\d+$/.test(raw)) {
    const seconds=Number(raw);
    if(Number.isSafeInteger(seconds)) retryAfterSeconds=seconds;
  } else if(raw && /^[A-Za-z]{3},/.test(raw)) {
    const date=Date.parse(raw);
    if(Number.isFinite(date)) retryAfterSeconds=Math.max(0,Math.ceil((date-Date.now())/1000));
  }
  const display=response.status===429 && retryAfterSeconds!==undefined
    ? `${message} Try again in ${retryAfterSeconds} seconds.` : message;
  const code=data && typeof data==='object' && 'code' in data && typeof data.code==='string'&&data.code.length<=128 ? data.code : undefined;
  const details=data&&typeof data==='object'&&'details' in data&&Array.isArray(data.details)?data.details.slice(0,500).filter((d):d is {rowNumber:number;field:string;message:string}=>!!d&&typeof d==='object'&&Number.isSafeInteger(d.rowNumber)&&typeof d.field==='string'&&typeof d.message==='string').map(d=>({rowNumber:d.rowNumber,field:d.field.slice(0,128),message:d.message.slice(0,2048)})):undefined;
  return new ApiError(response.status,display,retryAfterSeconds,code,details,!!code&&code.startsWith('IMPORT_'));
}
export class StaleSessionError extends Error {constructor(){super('This operation belongs to an earlier workspace. Reopen it in your current account.');this.name='StaleSessionError';}}
export function assertCurrent(isCurrent:()=>boolean) {if(!isCurrent())throw new StaleSessionError();}
// Keep the CSRF token in memory only; the browser manages the HttpOnly session cookie.
let csrf: { token: string; headerName: string } | null = null;
// Invalidate expiration side effects from requests belonging to an older login.
let sessionGeneration = 0;
let accountId:string|null=null;
const SESSION_CHANGE_KEY='campus:session-change';
function invalidateSession(){sessionGeneration++;csrf=null;accountId=null;}
function bindSessionAccount(id:string){
  if(accountId!==id){if(accountId!==null){sessionGeneration++;csrf=null;}accountId=id;}
}
function notifySessionChange(){
  // This is an invalidation signal only; never persist credentials or account data.
  try {window.localStorage.setItem(SESSION_CHANGE_KEY,crypto.randomUUID());} catch { /* The server account header still guards requests. */ }
}
export function subscribeSessionChanges(changed:()=>void):()=>void {
  const receive=(event:StorageEvent)=>{
    if(event.key!==SESSION_CHANGE_KEY&&event.key!==null)return;
    try {if(event.storageArea!==window.localStorage)return;} catch {return;}
    invalidateSession();changed();
  };
  window.addEventListener('storage',receive);
  return ()=>window.removeEventListener('storage',receive);
}
// Multi-request operations keep the same account boundary across all their requests.
export function sessionGuard():()=>boolean {
  const generation=sessionGeneration;
  return ()=>generation===sessionGeneration;
}
// Fetch the session token and detect when a static server is answering instead of Java.
export async function refreshCsrf(isCurrent:()=>boolean=()=>true) {
  const generation=sessionGeneration;assertCurrent(isCurrent);
  const response = await fetch('/api/auth/csrf', { credentials:'same-origin', cache:'no-store' });
  // Translate HTTP failures and notify the authentication gate when a protected session expires.
  if(!response.ok) throw await responseError(response, 'Cannot connect to the Java server. Start the backend and try again.');
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503, 'The Java API is not running. Start Spring Boot and try again.');
  const token:unknown=await response.json();assertCurrent(()=>generation===sessionGeneration&&isCurrent());
  if(!token||typeof token!=='object'||!('token' in token)||typeof token.token!=='string'||!token.token||!('headerName' in token)||typeof token.headerName!=='string'||!token.headerName)throw new ApiError(503,'The server returned an invalid security token. Refresh your connection and try again.');
  csrf={token:token.token,headerName:token.headerName};
}
// Attach CSRF headers to writes and include same-origin session credentials.
export async function request<T>(path:string, init:RequestInit={}, isCurrent:()=>boolean=()=>true):Promise<T> {
  const generation = sessionGeneration;
  const method=init.method ?? 'GET';
  const write=!['GET','HEAD','OPTIONS'].includes(method);
  const current=()=>generation===sessionGeneration&&isCurrent();assertCurrent(current);
  if(write&&!csrf) await refreshCsrf(current);
  assertCurrent(current);
  const headers=new Headers(init.headers);
  if(write && csrf) headers.set(csrf.headerName,csrf.token);
  headers.set('Accept','application/json');
  if(accountId&&path!=='/auth/me'&&path!=='/auth/login')headers.set('X-Campus-Account',accountId);
  let response:Response;
  try {response=await fetch('/api'+path,{...init,headers,credentials:'same-origin',cache:'no-store'});}
  catch {throw new ApiError(0,'Cannot reach the Java server. Check the connection and try again.');}
  if(!response.ok) {
    const error=await responseError(response, `Request failed (${response.status}). Please try again.`);
    // Account-scoped background requests must not expire a newer session after logout/login.
    if(response.status===401 && path!=='/auth/login' && path!=='/auth/me' && typeof window!=='undefined'
        && generation===sessionGeneration && isCurrent()) {
      invalidateSession();
      window.dispatchEvent(new Event('campus:session-expired'));
    }
    // Do not automatically replay writes; the server may already have committed the change.
    throw error;
  }
  // Handle successful responses with no JSON body, such as logout.
  if(response.status===204){assertCurrent(current);return undefined as T;}
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503,'The Java API is unavailable. Start Spring Boot and try again.');
  const data:unknown=await response.json();assertCurrent(current);return data as T;
}
// Serialize JSON consistently with its content-type header.
export const jsonBody=(value:unknown)=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
export interface User {id:string;displayName:string;email:string}
export interface VerificationAccepted {status:'VERIFICATION_REQUEST_ACCEPTED';message:string}
// Expose profile lookup and account actions to the authentication screen.
export const authApi={
  async me(){const user=await request<User>('/auth/me');bindSessionAccount(user.id);return user;},
  // Submit credentials in Spring Security\'s expected form format, then refresh the rotated CSRF token.
  async login(email:string,password:string) {
    invalidateSession();const current=sessionGuard();
    await refreshCsrf(current);
    const user=await request<User>('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({email:email.trim(),password})},current);
    csrf=null;await refreshCsrf(current);sessionGeneration++;accountId=user.id;notifySessionChange();return user;
  },
  // Accepted registration does not authenticate; mailbox confirmation and login are explicit.
  async register(displayName:string,email:string,password:string,includeSamples:boolean) {
    await refreshCsrf();
    return request<VerificationAccepted>('/auth/register',{method:'POST',...jsonBody({displayName:displayName.trim(),email:email.trim(),password,includeSamples})});
  },
  async resendVerification(email:string) {
    await refreshCsrf();
    return request<VerificationAccepted>('/auth/email-verification/resend',{method:'POST',...jsonBody({email:email.trim()})});
  },
  async confirmVerification(token:string,password:string) {
    await refreshCsrf();
    return request<{status:'EMAIL_VERIFIED'}>('/auth/email-verification/confirm',{method:'POST',...jsonBody({token,password})});
  },
  // End the server session and discard the in-memory CSRF token.
  async logout(){await request<void>('/auth/logout',{method:'POST'});invalidateSession();notifySessionChange();}
};
