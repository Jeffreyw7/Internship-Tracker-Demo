import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import ts from 'typescript';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {blankPostingDetails} from '../src/domain/postingDetails.ts';
async function load(name) {
  const url=new URL(`../src/components/${name}.tsx`,import.meta.url);
  const source=await readFile(url,'utf8');
  const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
  const require=createRequire(url);const module={exports:{}};
  new Function('require','module','exports',code)(key=>require(key.endsWith('/postingDetails')?key+'.ts':key.endsWith('/postingApi')?key+'.ts':key.endsWith('/applicationExport')?key+'.ts':key),module,module.exports);
  return module.exports;
}
test('posting form exposes all structured fields and prevents description editing after a failed read',async()=>{
  const {PostingFields}=await load('PostingFields');
  const html=renderToStaticMarkup(React.createElement(PostingFields,{value:blankPostingDetails(),onChange:()=>{},descriptionValue:'',descriptionAvailable:false,onRetry:()=>{},descriptionError:'Read failed'}));
  for(const field of ['Posting URL','Recruiter name','Recruiter email','Recruiter phone','Referral source','Minimum compensation','Maximum compensation','Currency','Pay period'])assert.ok(html.includes(field));
  assert.match(html,/<textarea[^>]*disabled/);assert.ok(html.includes('Read failed'));assert.ok(html.includes('Retry description'));
});
test('saved description displays literal HTML, capture time, and retained history notice',async()=>{
  const {PostingDetailsPanel}=await load('PostingDetailsPanel');
  const html=renderToStaticMarkup(React.createElement(PostingDetailsPanel,{applicationId:'a',value:blankPostingDetails(),loading:false,error:'',onRetry:()=>{},
    current:{applicationVersion:1,snapshot:{id:'s',revision:2,capturedAt:'2026-10-03T12:00:00Z',sourceUrl:'https://example.test',captureMethod:'MANUAL',descriptionText:'<script>text</script>\nSaved'}}}));
  assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));assert.ok(html.includes('Captured'));assert.ok(html.includes('Description history'));assert.ok(html.includes('Download description archive'));
});
