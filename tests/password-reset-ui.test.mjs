import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readdir, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Compile real components using the repository's TypeScript dependency; no UI mocks.
const workspace = resolve(import.meta.dirname,'..');
const temporary = await mkdtemp(join(import.meta.dirname,'.reset-render-'));
after(async () => {
  assert.equal(dirname(resolve(temporary)),resolve(import.meta.dirname));
  await rm(temporary,{recursive:true,force:true});
});
async function compile(directory) {
  for(const file of await readdir(directory,{withFileTypes:true})) {
    const source=join(directory,file.name);
    if(file.isDirectory()) { await compile(source); continue; }
    if(!/\.tsx?$/.test(file.name)) continue;
    const destination=join(temporary,source.slice(workspace.length+1).replace(/\.tsx?$/,'.mjs'));
    await mkdir(dirname(destination),{recursive:true});
    const result=ts.transpileModule(await readFile(source,'utf8'),{
      compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX},
      transformers:{before:[context => node => ts.visitNode(node,function visit(child) {
        if(ts.isImportDeclaration(child) && ts.isStringLiteral(child.moduleSpecifier) && child.moduleSpecifier.text.startsWith('.')) {
          const specifier=child.moduleSpecifier.text.replace(/\.tsx?$/,'')+'.mjs';
          return context.factory.updateImportDeclaration(child,child.modifiers,child.importClause,context.factory.createStringLiteral(specifier),child.attributes);
        }
        return ts.visitEachChild(child,visit,context);
      })]},
    });
    // The application provider requires a live browser. Expose its real context only
    // in this temporary build so static screen tests can supply appearance state.
    await writeFile(destination,result.outputText+(file.name==='ThemeProvider.tsx' ? '\nexport { ThemeContext };\n' : ''));
  }
}
await compile(join(workspace,'src'));
async function renderComponent(name,props) {
  const module=await import(pathToFileURL(join(temporary,'src/components/'+name+'.mjs')).href).catch(() => null);
  assert.ok(module?.default,name+' screen is missing');
  const {ThemeContext}=await import(pathToFileURL(join(temporary,'src/theme/ThemeProvider.mjs')).href);
  return renderToStaticMarkup(createElement(ThemeContext.Provider,{value:{preference:'system',canEdit:true,syncState:'idle',syncError:null,setPreference:()=>{},retrySync:()=>{}}},createElement(module.default,{
    token:'a'.repeat(42)+'A',completed:false,user:null,onReturn:()=>{},onRequest:()=>{},onSignOut:async()=>{},onComplete:()=>{},...props,
  })));
}
const renderReset=props => renderComponent('ResetPassword',props);

test('forgot-password form offers an explicit email submission and return to sign in', async () => {
  const html=await renderComponent('ForgotPassword',{initialEmail:'student@example.test'});
  assert.match(html,/type="email"/);
  assert.match(html,/Request reset link/);
  assert.match(html,/Back to sign in/);
});

test('lost reset responses explain possible success without claiming the password stayed unchanged', async () => {
  const module=await import(pathToFileURL(join(temporary,'src/components/ResetPassword.mjs')).href).catch(() => null);
  assert.equal(typeof module?.resetErrorMessage,'function');
  const lost=module.resetErrorMessage({status:0,message:'Connection lost'});
  assert.match(lost,/may already have changed/);
  assert.match(lost,/signing in/i);
  const invalid=module.resetErrorMessage({status:400,code:'PASSWORD_RESET_INVALID',message:'Invalid link'});
  assert.match(invalid,/expired or already been used/);
});

test('a signed-in link visitor must explicitly sign out before new password fields are shown', async () => {
  const html=await renderReset({user:{id:'other-account',displayName:'Other',email:'other@example.test'}});
  assert.match(html,/Sign out to reset a password/);
  assert.doesNotMatch(html,/autoComplete="new-password"/i);
});

test('anonymous reset form labels both new-password fields and offers policy guidance', async () => {
  const html=await renderReset({});
  assert.equal((html.match(/autoComplete="new-password"/gi) ?? []).length,2);
  assert.match(html,/15 characters/);
  assert.match(html,/Confirm new password/);
});

test('invalid reset links offer a new request without a password submission form', async () => {
  const html=await renderReset({token:null});
  assert.match(html,/Request a new reset link/);
  assert.doesNotMatch(html,/autoComplete="new-password"/i);
});

test('completion keeps verification help neutral and clears the password form', async () => {
  const html=await renderReset({token:null,completed:true});
  assert.match(html,/Sign in with your new password/);
  assert.match(html,/verification/i);
  assert.doesNotMatch(html,/autoComplete="new-password"/i);
});
