// Catch public-entry changes that hide verification or route expired sessions away from sign-in.
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { registerHooks } from 'node:module';
import ts from 'typescript';
import { JSDOM } from 'jsdom';

registerHooks({
  resolve(specifier, context, next) {
    try { return next(specifier, context); }
    catch (error) {
      if (specifier.startsWith('.') && context.parentURL) for (const extension of ['.ts', '.tsx']) {
        const url = new URL(specifier + extension, context.parentURL);
        if (fs.existsSync(fileURLToPath(url))) return next(url.href, context);
      }
      throw error;
    }
  },
  load(url, context, next) {
    if (url.startsWith('file:') && /\/src\/.*\.tsx?$/.test(url)) return {
      format: 'module', shortCircuit: true,
      source: ts.transpileModule(fs.readFileSync(fileURLToPath(url), 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
      }).outputText,
    };
    return next(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'CustomEvent']) globalThis[key] = dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
window.HTMLElement.prototype.scrollIntoView = function () {};
window.scrollTo = function () {};
const { createElement, act, StrictMode } = await import('react');
const { createRoot } = await import('react-dom/client');
const { default: AuthGate } = await import('../src/components/AuthGate.tsx');
const { ThemeProvider } = await import('../src/theme/ThemeProvider.tsx');
const { verificationLinks } = await import('../src/domain/verificationLink.ts');
const originalFetch = globalThis.fetch;
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
let root, container;
async function render(hash = '', signedIn = false) {
  window.history.replaceState(null, '', '/' + hash);
  globalThis.fetch = async url => {
    if (url === '/api/auth/me') return signedIn
      ? json({ id: 'landing-test-account', displayName: 'Ada', email: 'ada@example.test' })
      : json({ message: 'Sign in required' }, 401);
    if (url === '/api/preferences') return json({ themePreference: 'system' });
    if (String(url).startsWith('/api/applications')) return json({ items: [], total: 0, page: 0, size: 100 });
    return json({ message: 'Unavailable' }, 503);
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(createElement(StrictMode, null, createElement(ThemeProvider, null, createElement(AuthGate)))));
}
async function navigate(hash) {
  await act(async () => {
    window.history.pushState(null, '', '/' + hash);
    window.dispatchEvent(new Event('hashchange'));
  });
}
afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = undefined; container?.remove(); verificationLinks.dismiss();
  window.history.replaceState(null, '', '/'); window.localStorage.clear(); globalThis.fetch = originalFetch;
});

test('signed-out visitors can enter signup, switch to login, and return to the landing page', async () => {
  await render();
  assert.ok(container.querySelector('a[href="#create-account"]'), 'the landing page offers registration');
  assert.equal(container.querySelector('input[type="password"]'), null);
  await navigate('#create-account');
  assert.ok(container.querySelector('input[autocomplete="name"]'));
  assert.equal(container.querySelector('input[type="password"]').autocomplete, 'new-password');
  await navigate('#sign-in');
  assert.equal(container.querySelector('input[autocomplete="name"]'), null);
  assert.equal(container.querySelector('input[type="password"]').autocomplete, 'current-password');
  await navigate('#workspace');
  assert.ok(container.querySelector('#workspace'));
});

test('email verification takes priority over the landing page and still scrubs its token', async () => {
  await render();
  await navigate('#verify-email=' + 'a'.repeat(43));
  assert.equal(window.location.hash, '');
  assert.ok(container.querySelector('.verification-shell'));
  assert.ok(container.querySelector('input[type="password"]'));
  const back = [...container.querySelectorAll('button')].find(button => button.textContent.includes('Back to sign in'));
  await act(async () => back.click());
  assert.equal(container.querySelector('.verification-shell'), null);
  assert.ok(container.querySelector('input[autocomplete="username"]'));
});

test('an expired session presents sign-in and its recovery message even from the home URL', async () => {
  await render();
  await act(async () => window.dispatchEvent(new Event('campus:session-expired')));
  assert.ok(container.querySelector('input[autocomplete="current-password"]'));
  assert.match(container.querySelector('[role="alert"]').textContent, /session ended/i);
});

test('an existing signed-in session opens the workspace instead of the public landing page', async () => {
  await render('', true);
  assert.ok(container.querySelector('.app-shell'));
  assert.equal(container.querySelector('.landing-page'), null);
});

test('browser navigation leaves email assistance and returns to the requested public screen', async () => {
  await render('#sign-in');
  const assistance = [...container.querySelectorAll('button')].find(button => button.textContent.includes('Need a verification email?'));
  await act(async () => assistance.click());
  assert.ok(container.querySelector('.verification-shell'));
  await navigate('#top');
  assert.ok(container.querySelector('.landing-page'));
  assert.equal(container.querySelector('.verification-shell'), null);
  await navigate('#create-account');
  assert.ok(container.querySelector('input[autocomplete="new-password"]'));
});

test('password visibility never submits and resets when moving between account forms', async () => {
  await render('#sign-in');
  const reveal = container.querySelector('button[aria-label="Show password"]');
  assert.ok(reveal, 'passwords have an accessible visibility control');
  assert.equal(reveal.type, 'button');
  await act(async () => reveal.click());
  assert.equal(container.querySelector('input[autocomplete="current-password"]').type, 'text');
  assert.equal(container.querySelector('button[aria-label="Hide password"]').getAttribute('aria-pressed'), 'true');
  await navigate('#create-account');
  assert.equal(container.querySelector('input[autocomplete="new-password"]').type, 'password');
  assert.equal(container.querySelector('input[autocomplete="new-password"]').value, '');
});

test('account navigation puts keyboard focus on the destination heading', async () => {
  await render();
  await navigate('#create-account');
  assert.ok(document.activeElement === container.querySelector('h1'), 'signup heading receives focus');
  await navigate('#sign-in');
  assert.ok(document.activeElement === container.querySelector('h1'), 'sign-in heading receives focus');
});

test('signup submits the entered fields and sample choice before showing email verification', async () => {
  await render('#create-account');
  const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  async function fill(selector, value) {
    await act(async () => {
      const input = container.querySelector(selector);
      setValue.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }
  await fill('input[autocomplete="name"]', 'Ada Student');
  await fill('input[autocomplete="username"]', 'ada@example.test');
  await fill('input[autocomplete="new-password"]', 'A-test-password-12');
  await act(async () => container.querySelector('input[type="checkbox"]').click());
  let submitted;
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf') return json({ token: 'test-token', headerName: 'X-CSRF-TOKEN' });
    if (url === '/api/auth/register') {
      submitted = JSON.parse(options.body);
      return json({ status: 'VERIFICATION_REQUEST_ACCEPTED', message: 'Check your email.' }, 202);
    }
    throw new Error('Unexpected request: ' + url);
  };
  await act(async () => container.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  assert.deepEqual(submitted, { displayName: 'Ada Student', email: 'ada@example.test', password: 'A-test-password-12', includeSamples: false });
  assert.ok(container.querySelector('.verification-shell'));
  assert.equal(container.querySelector('input[autocomplete="email"]').value, 'ada@example.test');
  assert.equal(container.querySelector('input[name="password"]'), null);
  assert.equal(container.querySelector('.app-shell'), null, 'signup must not create an authenticated session');
});

test('the account home link dismisses a verification token before returning to the landing page', async () => {
  await render();
  await navigate('#verify-email=' + 'b'.repeat(43));
  await act(async () => container.querySelector('a[aria-label="Campus home"]').click());
  assert.equal(verificationLinks.getSnapshot(), null);
  assert.ok(container.querySelector('.landing-page'));
  assert.equal(container.querySelector('.verification-shell'), null);
});

test('skip to form keeps the active auth route and focuses its heading', async () => {
  await render('#sign-in');
  await act(async () => {
    container.querySelector('.landing-skip').click();
    await new Promise(resolve => setTimeout(resolve, 0));
  });
  assert.equal(window.location.hash, '#sign-in');
  assert.ok(document.activeElement === container.querySelector('h1'));
  assert.ok(container.querySelector('input[autocomplete="current-password"]'));
});
