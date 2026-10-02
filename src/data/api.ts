// Retain the HTTP status so callers can distinguish conflicts and expired sessions.
// Centralizes same-origin API requests, session handling, CSRF tokens, and user-facing errors.
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  constructor(status: number, message: string, code?: string) { super(message); this.name='ApiError'; this.status=status; this.code=code; }
}
// Keep the CSRF token in memory only; the browser manages the HttpOnly session cookie.
let csrf: { token: string; headerName: string } | null = null;
// Fetch the session token and detect when a static server is answering instead of Java.
export async function refreshCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials:'same-origin', cache:'no-store' });
  // Translate HTTP failures and notify the authentication gate when a protected session expires.
  if(!response.ok) throw new ApiError(response.status, 'Cannot connect to the Java server. Start the backend and try again.');
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503, 'The Java API is not running. Start Spring Boot and try again.');
  csrf=await response.json();
}
// Attach CSRF headers to writes and include same-origin session credentials.
export async function request<T>(path:string, init:RequestInit={}):Promise<T> {
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
    const data=await response.json().catch(()=>({}));
    if(response.status===401) {
      csrf=null;
      if(path!=='/auth/login' && path!=='/auth/me' && typeof window!=='undefined') window.dispatchEvent(new Event('campus:session-expired'));
    }
    // Do not automatically replay writes; the server may already have committed the change.
    throw new ApiError(response.status,data.message ?? `Request failed (${response.status}). Please try again.`,data.code);
  }
  // Handle successful responses with no JSON body, such as logout.
  if(response.status===204) return undefined as T;
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503,'The Java API is unavailable. Start Spring Boot and try again.');
  return response.json() as Promise<T>;
}
// Serialize JSON consistently with its content-type header.
export const jsonBody=(value:unknown)=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
export interface User {id:string;displayName:string;email:string}
interface PasswordResult {status:string;message:string}
export const passwordApi={
  requestReset:(email:string)=>request<PasswordResult>('/auth/password-reset/request',{method:'POST',...jsonBody({email:email.trim()})}),
  requestChange:(currentPassword:string)=>request<PasswordResult>('/auth/password-change/request',{method:'POST',...jsonBody({currentPassword})}),
  async confirmReset(token:string,newPassword:string){
    try{return await request<PasswordResult>('/auth/password-reset/confirm',{method:'POST',...jsonBody({token,newPassword})});}
    finally{csrf=null;}
  },
  async confirmChange(token:string,newPassword:string){
    try{return await request<PasswordResult>('/auth/password-change/confirm',{method:'POST',...jsonBody({token,newPassword})});}
    finally{csrf=null;}
  },
};
// Expose profile lookup and account actions to the authentication screen.
export const authApi={
  me:()=>request<User>('/auth/me'),
  // Submit credentials in Spring Security\'s expected form format, then refresh the rotated CSRF token.
  async login(email:string,password:string) {
    await refreshCsrf();
    const user=await request<User>('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({email:email.trim(),password})});
    csrf=null;await refreshCsrf();return user;
  },
  // Create the account first, then sign in using the submitted credentials.
  async register(displayName:string,email:string,password:string,includeSamples:boolean) {
    await refreshCsrf();
    await request<User>('/auth/register',{method:'POST',...jsonBody({displayName:displayName.trim(),email:email.trim(),password,includeSamples})});
    return this.login(email,password);
  },
  // End the server session and discard the in-memory CSRF token.
  async logout(){await request<void>('/auth/logout',{method:'POST'});csrf=null;}
};
