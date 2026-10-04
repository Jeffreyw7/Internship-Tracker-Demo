// Retain the HTTP status so callers can distinguish conflicts and expired sessions.
// Centralizes same-origin API requests, session handling, CSRF tokens, and user-facing errors.
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfterSeconds?: number;
  constructor(status: number, message: string, retryAfterSeconds?: number, code?: string) {
    super(message); this.name='ApiError'; this.status=status; this.retryAfterSeconds=retryAfterSeconds; this.code=code;
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
  const code=data && typeof data==='object' && 'code' in data && typeof data.code==='string' ? data.code : undefined;
  return new ApiError(response.status,display,retryAfterSeconds,code);
}
// Keep the CSRF token in memory only; the browser manages the HttpOnly session cookie.
let csrf: { token: string; headerName: string } | null = null;
// Fetch the session token and detect when a static server is answering instead of Java.
export async function refreshCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials:'same-origin', cache:'no-store' });
  // Translate HTTP failures and notify the authentication gate when a protected session expires.
  if(!response.ok) throw await responseError(response, 'Cannot connect to the Java server. Start the backend and try again.');
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503, 'The Java API is not running. Start Spring Boot and try again.');
  csrf=await response.json();
}
// Attach CSRF headers to writes and include same-origin session credentials.
export async function request<T>(path:string, init:RequestInit={}, isCurrent:()=>boolean=()=>true):Promise<T> {
  const method=init.method ?? 'GET';
  const write=!['GET','HEAD','OPTIONS'].includes(method);
  if(write&&!csrf) await refreshCsrf();
  const headers=new Headers(init.headers);
  if(write && csrf) headers.set(csrf.headerName,csrf.token);
  headers.set('Accept','application/json');
  let response:Response;
  try {response=await fetch('/api'+path,{...init,headers,credentials:'same-origin',cache:'no-store'});}
  catch {throw new ApiError(0,'Cannot reach the Java server. Check the connection and try again.');}
  if(!response.ok) {
    const error=await responseError(response, `Request failed (${response.status}). Please try again.`);
    // Account-scoped background requests must not expire a newer session after logout/login.
    if(response.status===401 && path!=='/auth/login' && path!=='/auth/me' && typeof window!=='undefined' && isCurrent()) window.dispatchEvent(new Event('campus:session-expired'));
    // Do not automatically replay writes; the server may already have committed the change.
    throw error;
  }
  // Handle successful responses with no JSON body, such as logout.
  if(response.status===204) return undefined as T;
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503,'The Java API is unavailable. Start Spring Boot and try again.');
  return response.json() as Promise<T>;
}
// Serialize JSON consistently with its content-type header.
export const jsonBody=(value:unknown)=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
export interface User {id:string;displayName:string;email:string}
export interface VerificationAccepted {status:'VERIFICATION_REQUEST_ACCEPTED';message:string}
// Expose profile lookup and account actions to the authentication screen.
export const authApi={
  me:()=>request<User>('/auth/me'),
  // Submit credentials in Spring Security\'s expected form format, then refresh the rotated CSRF token.
  async login(email:string,password:string) {
    await refreshCsrf();
    const user=await request<User>('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({email:email.trim(),password})});
    csrf=null;await refreshCsrf();return user;
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
  async logout(){await request<void>('/auth/logout',{method:'POST'});csrf=null;}
};
