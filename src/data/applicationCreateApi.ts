import {type Application,type ApplicationDraft,validApplication} from '../domain/application.ts';
import {toPayload} from './applicationApi.ts';
import {ApiError,request,jsonBody} from './api.ts';

export type CreateOutcome={status:'COMPLETED';application:Application}|{status:'CANCELLED';application:null};
function application(value:unknown):Application {
  if(!validApplication(value))throw new ApiError(502,'The save response was incomplete. Check save status before trying again.');
  return value;
}
function outcome(value:unknown):CreateOutcome {
  if(value&&typeof value==='object'&&'status' in value&&'application' in value) {
    if(value.status==='COMPLETED')return {status:'COMPLETED',application:application(value.application)};
    if(value.status==='CANCELLED'&&value.application===null)return {status:'CANCELLED',application:null};
  }
  throw new ApiError(502,'The save status response was incomplete. Check it again.');
}
export const applicationCreateApi={
  async create(draft:ApplicationDraft,key:string,current:()=>boolean):Promise<Application> {
    const body=jsonBody(toPayload(draft));
    return application(await request<unknown>('/applications',{...body,method:'POST',headers:{...body.headers,'Idempotency-Key':key}},current));
  },
  async status(key:string,current:()=>boolean):Promise<CreateOutcome> {
    return outcome(await request<unknown>(`/application-creates/${encodeURIComponent(key)}`,{},current));
  },
  async cancel(key:string,current:()=>boolean):Promise<CreateOutcome> {
    return outcome(await request<unknown>(`/application-creates/${encodeURIComponent(key)}`,{method:'DELETE'},current));
  }
};
