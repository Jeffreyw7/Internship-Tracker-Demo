export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) { super(message); this.name='ApiError'; this.status=status; }
}
let csrf: { token: string; headerName: string } | null = null;
export async function refreshCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials:'same-origin', cache:'no-store' });
  if(!response.ok) throw new ApiError(response.status, 'Cannot connect to the Java server. Start the backend and try again.');
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503, 'The Java API is not running. Start Spring Boot and try again.');
  csrf=await response.json();
}
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
    if(response.status===401 && path!=='/auth/login' && path!=='/auth/me' && typeof window!=='undefined') window.dispatchEvent(new Event('campus:session-expired'));
    // Do not automatically replay writes; the server may already have committed the change.
    throw new ApiError(response.status,data.message ?? `Request failed (${response.status}). Please try again.`);
  }
  if(response.status===204) return undefined as T;
  if(!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(503,'The Java API is unavailable. Start Spring Boot and try again.');
  return response.json() as Promise<T>;
}
export const jsonBody=(value:unknown)=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
export interface User {id:string;displayName:string;email:string}
export const authApi={
  me:()=>request<User>('/auth/me'),
  async login(email:string,password:string) {
    await refreshCsrf();
    const user=await request<User>('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({email:email.trim(),password})});
    csrf=null;await refreshCsrf();return user;
  },
  async register(displayName:string,email:string,password:string,includeSamples:boolean) {
    await refreshCsrf();
    await request<User>('/auth/register',{method:'POST',...jsonBody({displayName:displayName.trim(),email:email.trim(),password,includeSamples})});
    return this.login(email,password);
  },
  async logout(){await request<void>('/auth/logout',{method:'POST'});csrf=null;}
};
