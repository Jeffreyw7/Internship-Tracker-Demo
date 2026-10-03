import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {blankPostingDetails,postingPayload,validatePostingDraft} from '../src/domain/postingDetails.ts';
import {toPayload} from '../src/data/applicationApi.ts';
import {blankDraft} from '../src/domain/application.ts';
import {postingApi} from '../src/data/postingApi.ts';
import {applicationCsv} from '../src/domain/applicationExport.ts';
const originalFetch=globalThis.fetch;
afterEach(()=>{globalThis.fetch=originalFetch;});
const json=value=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});

test('posting payload preserves exact decimals and whitelists fields without replaying descriptions',()=>{
  const p={...blankPostingDetails(),postingUrl:' https://example.test/jobs ',recruiterName:' Alex ',
    compensationStatus:'PAID',compensationMin:'25.10',compensationMax:'35.50',compensationCurrency:'usd',compensationPeriod:'HOUR',
    descriptionRevision:7,ownerId:'forged',captureMethod:'forged'};
  const payload=toPayload({...blankDraft(),company:'Labs',role:'Intern',postingDetails:p});
  assert.equal(payload.postingDetails.compensationMin,'25.10');assert.equal(payload.postingDetails.compensationCurrency,'USD');
  assert.equal(payload.postingDetails.recruiterName,'Alex');
  assert.ok(!('descriptionRevision' in payload.postingDetails));assert.ok(!('ownerId' in payload.postingDetails));
  assert.ok(!('descriptionSnapshot' in payload.postingDetails));
  assert.equal(postingPayload({...p,descriptionSnapshot:'  lines\n<script>text</script> 😀  '}).descriptionSnapshot,'  lines\n<script>text</script> 😀  ');
  assert.equal(postingPayload({...p,descriptionSnapshot:''}).descriptionSnapshot,'');
});
test('posting validation rejects ambiguous ranges and unsafe links but permits unknown endpoints',()=>{
  const paid={...blankPostingDetails(),compensationStatus:'PAID',compensationCurrency:'USD',compensationPeriod:'HOUR',compensationMin:'25.10',compensationMax:null};
  assert.equal(validatePostingDraft(paid),null);
  for(const patch of [{compensationMin:'-1'},{compensationMin:'25.001'},{compensationMax:'24'},
    {compensationStatus:'UNPAID'},{compensationPeriod:null},{compensationCurrency:''},
    {postingUrl:'javascript:alert(1)'},{postingUrl:'https://user:pass@example.test'},{recruiterEmail:'invalid'},
    {descriptionSnapshot:'x'.repeat(100001)}]) assert.ok(validatePostingDraft({...paid,...patch}));
  assert.equal(validatePostingDraft(blankPostingDetails()),null);
});
test('archive export includes every paged revision and verifies the parent version',async()=>{
  let parentReads=0;
  globalThis.fetch=async url=>{
    if(url==='/api/applications/a'){parentReads++;return json({id:'a',version:4});}
    if(url.includes('/posting-descriptions?'))return json({content:[{id:url.endsWith('page=0')?'s2':'s1'}],totalPages:2,totalElements:2});
    if(url.endsWith('/s2'))return json({id:'s2',revision:2,descriptionText:'Latest'});
    if(url.endsWith('/s1'))return json({id:'s1',revision:1,descriptionText:'Old'});
    throw new Error(url);
  };
  const archive=await postingApi.archive('a');
  assert.deepEqual(archive.snapshots.map(s=>s.descriptionText),['Latest','Old']);assert.equal(parentReads,2);
});
test('archive export retries changed parents and never returns partial files',async()=>{
  let parentReads=0;
  globalThis.fetch=async url=>{
    if(url==='/api/applications/a')return json({id:'a',version:++parentReads===1?1:2});
    if(url.includes('/posting-descriptions?'))return json({content:[{id:'s1'}],totalPages:1,totalElements:1});
    return json({id:'s1',revision:1,descriptionText:'Saved'});
  };
  const archive=await postingApi.archive('a');assert.equal(archive.application.version,2);assert.equal(parentReads,4);
  globalThis.fetch=async url=>url.endsWith('/s1')?new Response(JSON.stringify({message:'Read failed'}),{status:500,headers:{'Content-Type':'application/json'}}):
    url.includes('/posting-descriptions?')?json({content:[{id:'s1'}],totalPages:1,totalElements:1}):json({id:'a',version:2});
  await assert.rejects(()=>postingApi.archive('a'),/Read failed/);
});
test('summary CSV includes contacts and compensation and escapes formulas and multiline values',()=>{
  const p={...blankPostingDetails(),recruiterName:'=danger()',referralSource:'Fair, campus',compensationMin:'25.10',compensationPeriod:'HOUR',compensationCurrency:'USD'};
  const csv=applicationCsv([{...blankDraft(),company:'Labs',role:'Intern',notes:'line 1\n"line 2"',postingDetails:p}]);
  assert.ok(csv.startsWith('\uFEFF'));assert.ok(csv.includes('Recruiter name'));assert.ok(csv.includes('"\'=danger()"'));
  assert.ok(csv.includes('"Fair, campus"'));assert.ok(csv.includes('"25.10"'));assert.ok(csv.includes('"line 1\n""line 2"""'));
  assert.ok(!csv.includes('descriptionSnapshot'));
});
