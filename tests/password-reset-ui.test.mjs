import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readdir, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';
import { act, createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { JSDOM } from 'jsdom';

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

test('window focus preserves recovery input, pending confirmation, and its result', async () => {
  const token='a'.repeat(42)+'A';
  const dom=new JSDOM(`<!doctype html><div id="root"></div>`,{url:`http://localhost/#reset-password=${token}`});
  const previous={window:globalThis.window,document:globalThis.document,HTMLElement:globalThis.HTMLElement,
    Event:globalThis.Event,fetch:globalThis.fetch,actEnvironment:globalThis.IS_REACT_ACT_ENVIRONMENT};
  const previousNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  Object.assign(globalThis,{window:dom.window,document:dom.window.document,HTMLElement:dom.window.HTMLElement,
    Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true});
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:dom.window.navigator});
  let finishConfirmation;
  let profileCalls=0;
  globalThis.fetch=async url=>{
    if(url==='/api/auth/me') { profileCalls++; return new Response(JSON.stringify({message:'Sign in required'}),{status:401,headers:{'Content-Type':'application/json'}}); }
    if(url==='/api/auth/csrf') return new Response(JSON.stringify({token:'fresh',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}});
    if(url==='/api/auth/password-reset/confirm') return new Promise(resolve=>{finishConfirmation=resolve;});
    throw new Error(`Unexpected request: ${url}`);
  };
  const container=dom.window.document.getElementById('root');
  const {createRoot}=await import('react-dom/client');
  const root=createRoot(container);
  try {
    const authModule=await import(pathToFileURL(join(temporary,'src/components/AuthGate.mjs')).href);
    const {ThemeContext}=await import(pathToFileURL(join(temporary,'src/theme/ThemeProvider.mjs')).href);
    const theme={preference:'system',canEdit:true,syncState:'idle',syncError:null,setPreference:()=>{},retrySync:()=>{},bindAccount:()=>{}};
    await act(async()=>{root.render(createElement(ThemeContext.Provider,{value:theme},createElement(authModule.default)));});
    await act(async()=>{await new Promise(resolve=>setTimeout(resolve,0));});

    const passwordInputs=()=>Array.from(container.querySelectorAll('input[type="password"]'));
    const setInput=(input,value)=>{
      Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(input,value);
      input.dispatchEvent(new dom.window.Event('input',{bubbles:true}));
    };
    let [password,confirmation]=passwordInputs();
    assert.ok(password&&confirmation,'the reset form should mount after the unauthenticated profile check');
    const secret='a sufficiently long passphrase';
    await act(async()=>{setInput(password,secret);});
    await act(async()=>{dom.window.dispatchEvent(new dom.window.Event('focus'));await new Promise(resolve=>setTimeout(resolve,0));});
    [password,confirmation]=passwordInputs();
    assert.deepEqual([password.value,confirmation.value],[secret,''],'a focus profile refresh must preserve text entered in the recovery form');

    await act(async()=>{setInput(confirmation,secret);});
    await act(async()=>{container.querySelector('form').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true}));});
    await act(async()=>{await new Promise(resolve=>setTimeout(resolve,0));});
    assert.equal(typeof finishConfirmation,'function','the reset request should be pending');
    assert.match(container.textContent,/Resetting/);
    const callsBeforePendingFocus=profileCalls;
    await act(async()=>{dom.window.dispatchEvent(new dom.window.Event('focus'));});
    assert.equal(profileCalls,callsBeforePendingFocus+1,'a focus profile refresh may run while reset confirmation is pending');
    [password,confirmation]=passwordInputs();
    assert.deepEqual([password.value,confirmation.value],[secret,secret]);

    await act(async()=>{finishConfirmation(new Response(JSON.stringify({message:'Temporary reset failure'}),{status:503,headers:{'Content-Type':'application/json'}}));});
    assert.match(container.textContent,/may already have changed/);
    await act(async()=>{dom.window.dispatchEvent(new dom.window.Event('focus'));await new Promise(resolve=>setTimeout(resolve,0));});
    assert.match(container.textContent,/may already have changed/,'a focus refresh after the request settles must preserve its result message');
    assert.equal(profileCalls,callsBeforePendingFocus+2,'settled recovery forms may refresh the profile without unmounting');

    [password,confirmation]=passwordInputs();
    await act(async()=>{setInput(password,secret);setInput(confirmation,secret);});
    await act(async()=>{container.querySelector('form').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true}));});
    await act(async()=>{await new Promise(resolve=>setTimeout(resolve,0));});
    assert.equal(typeof finishConfirmation,'function','a second reset request should also be pending');
    const callsBeforeSuccessFocus=profileCalls;
    await act(async()=>{dom.window.dispatchEvent(new dom.window.Event('focus'));});
    assert.equal(profileCalls,callsBeforeSuccessFocus+1);
    [password,confirmation]=passwordInputs();
    assert.deepEqual([password.value,confirmation.value],[secret,secret],'focus must preserve both fields during the committed attempt');
    await act(async()=>{finishConfirmation(new Response(JSON.stringify({status:'PASSWORD_RESET_COMPLETE',message:'Password reset successfully.'}),{headers:{'Content-Type':'application/json'}}));});
    assert.match(container.textContent,/Sign in with your new password/);
    await act(async()=>{dom.window.dispatchEvent(new dom.window.Event('focus'));await new Promise(resolve=>setTimeout(resolve,0));});
    assert.match(container.textContent,/Sign in with your new password/,'focus must keep the committed reset result visible');
  } finally {
    await act(async()=>root.unmount());
    dom.window.close();
    for(const [key,value] of Object.entries(previous)) {
      if(value===undefined) delete globalThis[key]; else globalThis[key]=value;
    }
    if(previousNavigator) Object.defineProperty(globalThis,'navigator',previousNavigator); else delete globalThis.navigator;
  }
});
