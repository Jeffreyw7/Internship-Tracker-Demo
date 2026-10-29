import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {csvCell,applicationCsv} from '../src/domain/applicationCsv.ts';
import {parsePreview,parseReceipt,parseImportStatus,readImportPointer,writeImportPointer} from '../src/domain/applicationImport.ts';
import {applicationImportApi,ImportOutcomeUnknown} from '../src/data/applicationImportApi.ts';
import {request,StaleSessionError,refreshCsrf} from '../src/data/api.ts';
import {createImportWorkflow} from '../src/domain/importWorkflow.ts';
import {commitReviewedImport} from '../src/data/importCommitRecovery.ts';
import {ApiError} from '../src/data/api.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>globalThis.fetch=originalFetch);
const counts={added:1,updated:0,skipped:0,unchanged:0,errors:0};
const preview={id:'11111111-1111-4111-8111-111111111111',schemaVersion:1,policyVersion:'1',createdAt:'2026-10-15T00:00:00Z',expiresAt:'2026-10-15T00:30:00Z',reviewDigest:'digest',committable:true,headers:['Company'],rows:[],counts,issues:[]};
const receipt={importId:preview.id,receiptId:preview.id,completedAt:preview.createdAt,retainUntil:preview.expiresAt,counts,rows:[]};
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
test('exports quote literals and reduce formula risk after whitespace and controls',()=>{
  assert.equal(csvCell(' \t=1+1'),'"\' \t=1+1"');assert.equal(csvCell('a,"b"'),'"a,""b"""');
  assert.equal(csvCell('\u0085\u009f@SUM(A1)'),'"\'\u0085\u009f@SUM(A1)"');
  const csv=applicationCsv([{id:'id',company:'=FORMULA',role:'R',location:'',status:'Saved',appliedDate:'',deadline:'',followUpDate:'',notes:'',stages:[{id:'secret',name:'Call',date:'',state:'Planned'}]}]);
  assert.ok(csv.includes('Application ID'));assert.ok(csv.includes('Interview stages JSON'));assert.ok(!csv.includes('secret'));
});
test('runtime validators reject malformed nested envelopes and contradictory status',()=>{
  assert.equal(parsePreview(preview).id,preview.id);assert.equal(parseReceipt(receipt).importId,preview.id);
  assert.throws(()=>parsePreview({...preview,counts:{...counts,errors:-1}}));
  assert.throws(()=>parsePreview({...preview,rows:[{rowNumber:2,issues:[]}]}));
  assert.throws(()=>parseImportStatus({status:'COMPLETED',receipt:null}));
  assert.throws(()=>parseReceipt({...receipt,rows:[{rowNumber:2,action:'Add',applicationId:42,resultVersion:0}]}));
});
test('preview uses multipart without a manual Content-Type and commit validates its result',async()=>{
  const calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return url.endsWith('/csrf')?json({token:'token',headerName:'X-CSRF-TOKEN'}):json(url.endsWith('/commit')?receipt:preview);};
  await refreshCsrf();await applicationImportApi.preview(new File(['Company\nA'],'a.csv'),{schemaVersion:1,mapping:{},dateFormat:'ISO',rows:[]});
  assert.ok(calls.at(-1).init.body instanceof FormData);assert.equal(calls.at(-1).init.headers.has('Content-Type'),false);
  await applicationImportApi.commit(preview,()=>true);assert.deepEqual(JSON.parse(calls.at(-1).init.body),{reviewDigest:'digest',acknowledgement:{existingApplicationsToUpdate:0}});
});
test('invalid successful commit body is unknown and never automatically replayed',async()=>{
  let writes=0;globalThis.fetch=async(url)=>{if(url.endsWith('/csrf'))return json({token:'token',headerName:'X-CSRF-TOKEN'});writes++;return json({wrong:true});};
  await assert.rejects(()=>applicationImportApi.commit(preview,()=>true),ImportOutcomeUnknown);assert.equal(writes,1);
});
test('guards stop stale operations before dispatch and after awaited CSRF',async()=>{
  let calls=0;globalThis.fetch=async()=>{calls++;return json({});};
  await assert.rejects(()=>request('/application-imports/x/commit',{method:'POST'},()=>false),StaleSessionError);assert.equal(calls,0);
  let finishCsrf,csrfStarted,current=true,writes=0;
  const started=new Promise(resolve=>{csrfStarted=resolve;});
  globalThis.fetch=async(url)=>url.endsWith('/csrf')?new Promise(resolve=>{finishCsrf=resolve;csrfStarted();}):(writes++,json(receipt));
  const pending=applicationImportApi.commit(preview,()=>current);const rejected=assert.rejects(pending,StaleSessionError);
  await started;current=false;finishCsrf(json({token:'new-token',headerName:'X-CSRF-TOKEN'}));await rejected;assert.equal(writes,0);
});
test('status only reads and follows runtime-validated server envelopes',async()=>{
  const calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return json({status:'COMPLETED',receipt});};
  const result=await applicationImportApi.status(preview.id);assert.equal(result.status,'COMPLETED');assert.equal(calls[0].init.method,undefined);assert.equal(calls.length,1);
  globalThis.fetch=async()=>json({status:'COMPLETED',receipt:{...receipt,importId:'22222222-2222-4222-8222-222222222222'}});
  await assert.rejects(()=>applicationImportApi.status(preview.id),/Mismatched/);
});
for(const response of [()=>json({message:'busy'},503),()=>new Response('<html>proxy</html>',{headers:{'Content-Type':'text/html'}}),()=>new Response('{',{headers:{'Content-Type':'application/json'}})]) {
  test('ambiguous dispatched commit failures hold uncertainty and do not replay',async()=>{
    let writes=0;globalThis.fetch=async(url)=>url.endsWith('/csrf')?json({token:'token',headerName:'X-CSRF-TOKEN'}):(writes++,response());
    await assert.rejects(()=>applicationImportApi.commit(preview),ImportOutcomeUnknown);assert.equal(writes,1);
  });
}
test('recovery storage retains only account and import references and never expires locally',()=>{
  const values=new Map();const storage={getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
  writeImportPointer(storage,'owner',preview.id);assert.deepEqual(readImportPointer(storage,'owner'),{ownerId:'owner',previewId:preview.id});
  assert.equal(readImportPointer(storage,'another'),null);assert.equal(values.size,0);
});
test('corrections invalidate confirmation and supersede through more than five revisions',async()=>{
  const inputs=[];let number=0;
  const workflow=createImportWorkflow({guard:()=>()=>true,preview:async(file,options)=>{inputs.push(options);return {...preview,id:`00000000-0000-4000-8000-${String(++number).padStart(12,'0')}`};},status:async()=>{throw new Error('unexpected');}});
  workflow.activate();workflow.chooseFile(new File(['x'],'x.csv'));await workflow.createPreview();
  for(let index=0;index<7;index++){
    const predecessor=workflow.getSnapshot().preview.id;workflow.updateRow(1,{values:{company:String(index)}});
    assert.equal(workflow.getSnapshot().current,false);await workflow.createPreview();assert.equal(inputs.at(-1).supersedesPreviewId,predecessor);assert.equal(workflow.getSnapshot().current,true);
  }
  assert.equal(inputs.length,8);
});
test('lost successor responses reconcile by GET without uploading or committing again',async()=>{
  let uploads=0;const reads=[];const successor={...preview,id:'22222222-2222-4222-8222-222222222222'};
  const workflow=createImportWorkflow({guard:()=>()=>true,preview:async()=>{if(++uploads===1)return preview;throw new Error('Response lost');},status:async id=>{reads.push(id);return id===preview.id?{status:'SUPERSEDED',successorId:successor.id}:{status:'PREVIEW',preview:successor};}});
  workflow.activate();workflow.chooseFile(new File(['x'],'x.csv'));await workflow.createPreview();workflow.edit(o=>({...o,dateFormat:'DMY'}));await workflow.createPreview();
  assert.equal(workflow.getSnapshot().uncertainPredecessor,preview.id);await workflow.recoverPreview();
  assert.deepEqual(reads,[preview.id,successor.id]);assert.equal(uploads,2);assert.equal(workflow.getSnapshot().preview.id,successor.id);assert.equal(workflow.getSnapshot().current,true);
});
test('draft changes, account changes and disposal prevent stale preview effects',async()=>{
  for(const invalidate of ['edit','account','dispose']) {
    let finish,current=true;const workflow=createImportWorkflow({guard:()=>()=>current,preview:async()=>new Promise(resolve=>finish=resolve),status:async()=>{throw new Error('unexpected');}});
    workflow.activate();workflow.chooseFile(new File(['x'],'x.csv'));const pending=workflow.createPreview();
    if(invalidate==='edit')workflow.edit(o=>({...o,dateFormat:'MDY'}));else if(invalidate==='account')current=false;else workflow.dispose();
    finish(preview);await pending;assert.equal(workflow.getSnapshot().preview,null);if(invalidate==='edit')assert.equal(workflow.getSnapshot().pending,false);
  }
});
test('recovering an older successor cannot confirm edits made after its lost response',async()=>{
  let uploads=0;const successor={...preview,id:'22222222-2222-4222-8222-222222222222'};
  const workflow=createImportWorkflow({guard:()=>()=>true,preview:async()=>{if(++uploads===1)return preview;throw new Error('Lost');},status:async id=>id===preview.id?{status:'SUPERSEDED',successorId:successor.id}:{status:'PREVIEW',preview:successor}});
  workflow.activate();workflow.chooseFile(new File(['x'],'x.csv'));await workflow.createPreview();workflow.edit(o=>({...o,dateFormat:'DMY'}));await workflow.createPreview();
  workflow.edit(o=>({...o,dateFormat:'MDY'}));await workflow.recoverPreview();assert.equal(workflow.getSnapshot().preview.id,successor.id);assert.equal(workflow.getSnapshot().current,false);assert.equal(workflow.getSnapshot().options.dateFormat,'MDY');
});
test('an explicit retry rejection cannot release an earlier unknown commit lock',async()=>{
  for(const error of [new ApiError(429,'Slow down',1,'IMPORT_RATE_LIMITED',undefined,true),new ApiError(403,'CSRF'),new Error('Cannot refresh CSRF')]) {
    let held=false,completions=0;
    const deps={current:()=>true,remember:()=>{held=true;},release:()=>{held=false;},complete:async result=>{completions++;held=false;return result;},commit:async()=>{throw new ImportOutcomeUnknown(new Error('Lost'));}};
    await assert.rejects(()=>commitReviewedImport(deps,preview,false),ImportOutcomeUnknown);assert.equal(held,true);
    await assert.rejects(()=>commitReviewedImport({...deps,commit:async()=>{throw error;}},preview,true));assert.equal(held,true);assert.equal(completions,0);
    await commitReviewedImport({...deps,commit:async()=>receipt},preview,true);assert.equal(held,false);assert.equal(completions,1);
  }
});
test('terminal missing preview needs explicit duplicate-risk acknowledgement before a fresh review',async()=>{
  let uploads=0;const workflow=createImportWorkflow({guard:()=>()=>true,preview:async()=>{if(++uploads===1)return preview;throw new Error('Lost');},status:async()=>{throw new ApiError(404,'Import not found',undefined,'IMPORT_NOT_FOUND',undefined,true);},terminal:()=> 'MISSING'});
  workflow.activate();workflow.chooseFile(new File(['x'],'x.csv'));await workflow.createPreview();workflow.edit(o=>({...o,dateFormat:'DMY'}));await workflow.createPreview();await workflow.recoverPreview();
  assert.equal(workflow.getSnapshot().requiresDuplicateAcknowledgement,true);workflow.restartReview(false);assert.ok(workflow.getSnapshot().retiredReference);await workflow.createPreview();assert.equal(uploads,2);
  workflow.restartReview(true);assert.equal(workflow.getSnapshot().preview,null);assert.equal(workflow.getSnapshot().retiredReference,null);assert.equal(uploads,2);
});
