// Load the account's full history through immutable cursor boundaries for local filtering and export.
import type { Application } from '../domain/application.ts';
import { ApiError, request, sessionGuard } from './api.ts';

interface ApplicationHistoryPage {content:Application[];nextCursor:string|null}
const pageSize=100;
const invalidHistory=()=>new ApiError(503,'The server returned an invalid application history. Refresh and try again.');

export async function listApplicationHistory(guard:()=>boolean=()=>true):Promise<Application[]> {
  const accountCurrent=sessionGuard();const isCurrent=()=>accountCurrent()&&guard();
  const apps:Application[]=[],ids=new Set<string>(),cursors=new Set<string>();
  let cursor:string|null=null;
  do {
    const path='/applications/history?size='+pageSize+(cursor===null?'':'&cursor='+encodeURIComponent(cursor));
    const page:ApplicationHistoryPage=await request<ApplicationHistoryPage>(path,{},isCurrent);
    if(!page||!Array.isArray(page.content)||page.content.length>pageSize
      ||!(page.nextCursor===null||(typeof page.nextCursor==='string'&&page.nextCursor.length>0&&page.nextCursor.length<=512)))throw invalidHistory();
    if(page.nextCursor!==null&&(page.content.length===0||cursors.has(page.nextCursor)))throw invalidHistory();
    for(const application of page.content) {
      if(!application||typeof application.id!=='string'||!application.id||ids.has(application.id))throw invalidHistory();
      ids.add(application.id);apps.push(application);
    }
    cursor=page.nextCursor;
    if(cursor!==null)cursors.add(cursor);
  } while(cursor!==null);
  return apps;
}
