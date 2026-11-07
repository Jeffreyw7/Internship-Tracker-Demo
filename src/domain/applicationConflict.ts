// Compare an editor's base, local draft, and latest saved record without changing any of them.
import type {ApplicationDraft} from './application.ts';

export const applicationFields = [
  ['company','Company'],['role','Role'],['location','Location'],['status','Status'],
  ['appliedDate','Application date'],['deadline','Application deadline'],['followUpDate','Follow-up date'],
  ['notes','Notes'],['stages','Interview stages'],
] as const satisfies ReadonlyArray<readonly [keyof ApplicationDraft,string]>;

// Only editable fields belong in a draft; server metadata stays in the saved record.
export function editableDraft(value:ApplicationDraft):ApplicationDraft {
  return {company:value.company,role:value.role,location:value.location,status:value.status,
    appliedDate:value.appliedDate,deadline:value.deadline,followUpDate:value.followUpDate,notes:value.notes,
    stages:structuredClone(value.stages)};
}

export function compareApplicationDraft(base:ApplicationDraft,local:ApplicationDraft,latest:ApplicationDraft) {
  const same=(a:ApplicationDraft[keyof ApplicationDraft],b:ApplicationDraft[keyof ApplicationDraft])=>JSON.stringify(a)===JSON.stringify(b);
  const changes=applicationFields.flatMap(([field,label])=>{
    const localChanged=!same(base[field],local[field]),remoteChanged=!same(base[field],latest[field]);
    return localChanged||remoteChanged?[{field,label,localChanged,remoteChanged,overlap:localChanged&&remoteChanged&&!same(local[field],latest[field])}]:[];
  });
  // Stages are reviewed as one ordered field: silently merging individual rounds could lose deletions or reorder them.
  const merged=editableDraft(latest);
  for(const {field,localChanged} of changes)if(localChanged)Object.assign(merged,{[field]:structuredClone(local[field])});
  return {changes,merged,hasOverlaps:changes.some(change=>change.overlap)};
}

export function conflictValue(value:ApplicationDraft[keyof ApplicationDraft]):string {
  return Array.isArray(value)?value.map(stage=>`${stage.name} · ${stage.state}${stage.date?' · '+stage.date:''}`).join('\n')||'No stages':value||'Not set';
}
