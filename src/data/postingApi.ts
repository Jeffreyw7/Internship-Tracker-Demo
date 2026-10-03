import {request} from './api.ts';
import type {Application} from '../domain/application.ts';
export interface SnapshotMetadata {id:string;revision:number;sourceUrl:string;capturedAt:string;captureMethod:string}
export interface DescriptionSnapshot extends SnapshotMetadata {descriptionText:string}
export interface CurrentDescription {applicationVersion:number;snapshot:DescriptionSnapshot|null}
export interface DescriptionHistory {content:SnapshotMetadata[];totalElements:number;totalPages:number;page:number;size:number}
export const postingApi={
  current:(id:string,signal?:AbortSignal)=>request<CurrentDescription>(`/applications/${id}/posting-description`,{signal}),
  history:(id:string,page=0,signal?:AbortSignal)=>request<DescriptionHistory>(`/applications/${id}/posting-descriptions?size=20&page=${page}`,{signal}),
  snapshot:(id:string,snapshotId:string,signal?:AbortSignal)=>request<DescriptionSnapshot>(`/applications/${id}/posting-descriptions/${snapshotId}`,{signal}),
  async archive(id:string) {
    for(let attempt=0;attempt<3;attempt++) {
      const application=await request<Application>(`/applications/${id}`);
      const first=await this.history(id);
      const metadata=[...first.content];
      for(let page=1;page<first.totalPages;page++)metadata.push(...(await this.history(id,page)).content);
      const unique=[...new Map(metadata.map(s=>[s.id,s])).values()];
      const snapshots:DescriptionSnapshot[]=[];
      // Bound concurrent reads and memory growth to the saved history, not every application.
      for(const item of unique)snapshots.push(await this.snapshot(id,item.id));
      const after=await request<Application>(`/applications/${id}`);
      if(application.version!==after.version)continue;
      if(unique.length!==first.totalElements)throw new Error('The description archive was incomplete. Try again.');
      return {exportedAt:new Date().toISOString(),application,snapshots};
    }
    throw new Error('This application keeps changing. Try the archive download again after edits finish.');
  }
};
