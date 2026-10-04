import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { authApi, ApiError } from '../src/data/api.ts';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const accepted = { status: 'VERIFICATION_REQUEST_ACCEPTED', message: 'Check your email.' };
const csrf = { token: 'fresh-token', headerName: 'X-CSRF-TOKEN' };

test('registration returns acceptance without creating an authenticated session', async () => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return url === '/api/auth/csrf' ? json(csrf) : json(accepted, 202);
  };
  assert.deepEqual(await authApi.register(' Ada ', ' ada@example.com ', 'Password123!', false), accepted);
  assert.deepEqual(calls.map(call => call.url), ['/api/auth/csrf', '/api/auth/register']);
  assert.deepEqual(JSON.parse(calls[1].options.body), { displayName: 'Ada', email: 'ada@example.com', password: 'Password123!', includeSamples: false });
});

test('verification-required login preserves its machine-readable code', async () => {
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : json({ message: 'Verify your email.', code: 'EMAIL_VERIFICATION_REQUIRED' }, 403);
  await assert.rejects(authApi.login('ada@example.com', 'Password123!'), error => error instanceof ApiError && error.status === 403 && error.code === 'EMAIL_VERIFICATION_REQUIRED');
});

test('resend trims the address and sends a same-origin CSRF-protected request', async () => {
  const calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return url === '/api/auth/csrf' ? json(csrf) : json(accepted, 202); };
  assert.deepEqual(await authApi.resendVerification(' ada@example.com '), accepted);
  assert.deepEqual(calls.map(call => call.url), ['/api/auth/csrf', '/api/auth/email-verification/resend']);
  assert.deepEqual(JSON.parse(calls[1].options.body), { email: 'ada@example.com' });
  assert.equal(calls[1].options.credentials, 'same-origin');
  assert.equal(calls[1].options.headers.get('X-CSRF-TOKEN'), 'fresh-token');
});

test('confirmation sends the password and token only in its protected request body', async () => {
  const calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return url === '/api/auth/csrf' ? json(csrf) : json({ status: 'EMAIL_VERIFIED' }); };
  assert.deepEqual(await authApi.confirmVerification('a'.repeat(43), 'Password123!'), { status: 'EMAIL_VERIFIED' });
  assert.deepEqual(calls.map(call => call.url), ['/api/auth/csrf', '/api/auth/email-verification/confirm']);
  assert.deepEqual(JSON.parse(calls[1].options.body), { token: 'a'.repeat(43), password: 'Password123!' });
  assert.equal(calls[1].options.credentials, 'same-origin');
  assert.equal(calls[1].options.headers.get('X-CSRF-TOKEN'), 'fresh-token');
});

test('a lost confirmation response is not automatically replayed', async () => {
  let writes = 0;
  globalThis.fetch = async url => { if (url === '/api/auth/csrf') return json(csrf); writes++; throw new Error('Response lost'); };
  await assert.rejects(authApi.confirmVerification('a'.repeat(43), 'Password123!'), error => error instanceof ApiError && error.status === 0);
  assert.equal(writes, 1);
});

test('invalid confirmation remains distinct from a CSRF failure', async () => {
  globalThis.fetch = async url => url === '/api/auth/csrf' ? json(csrf) : json({ message: 'Invalid verification.', code: 'VERIFICATION_INVALID' }, 400);
  await assert.rejects(authApi.confirmVerification('a'.repeat(43), 'WrongPassword1!'), error => error.status === 400 && error.code === 'VERIFICATION_INVALID');
});

function browserFixture(hash) {
  const listeners = new Set();
  const replacements = [];
  const location = { hash, pathname: '/', search: '?theme=dark' };
  return {
    location, replacements,
    history: { state: { existing: true }, replaceState(state, title, url) { replacements.push({ state, title, url }); location.hash = ''; } },
    addEventListener(event, listener) { assert.equal(event, 'hashchange'); listeners.add(listener); },
    removeEventListener(event, listener) { listeners.delete(listener); },
    navigate(hash) { location.hash = hash; for (const listener of listeners) listener(); },
  };
}

test('verification link is scrubbed before rendering and survives repeated snapshot reads', async () => {
  const { createVerificationLinkStore } = await import('../src/domain/verificationLink.ts');
  const browser = browserFixture('#verify-email=' + 'a'.repeat(43));
  const store = createVerificationLinkStore(browser);
  assert.equal(browser.location.hash, '');
  assert.deepEqual(browser.replacements, [{ state: { existing: true }, title: '', url: '/?theme=dark' }]);
  const first = store.getSnapshot();
  assert.deepEqual(first, { token: 'a'.repeat(43) });
  assert.equal(store.getSnapshot(), first);
  const unsubscribe = store.subscribe(() => {});
  unsubscribe();
  store.subscribe(() => {});
  assert.equal(store.getSnapshot(), first);
  store.dismiss();
  assert.equal(store.getSnapshot(), null);
});

test('a new verification link replaces the old token and notifies subscribers', async () => {
  const { createVerificationLinkStore } = await import('../src/domain/verificationLink.ts');
  const browser = browserFixture('');
  const store = createVerificationLinkStore(browser);
  const snapshots = [];
  const unsubscribe = store.subscribe(() => snapshots.push(store.getSnapshot()));
  browser.navigate('#verify-email=' + 'b'.repeat(43));
  browser.navigate('#verify-email=' + 'c'.repeat(43));
  assert.deepEqual(snapshots, [{ token: 'b'.repeat(43) }, { token: 'c'.repeat(43) }]);
  assert.equal(browser.location.hash, '');
  unsubscribe();
});

test('malformed verification fragments are removed without becoming usable tokens', async () => {
  const { createVerificationLinkStore } = await import('../src/domain/verificationLink.ts');
  for (const hash of ['#verify-email=', '#verify-email=short', '#verify-email=' + 'a'.repeat(44), '#verify-email=' + 'a'.repeat(42) + '!']) {
    const browser = browserFixture(hash);
    assert.deepEqual(createVerificationLinkStore(browser).getSnapshot(), { token: null });
    assert.equal(browser.location.hash, '');
  }
  const browser = browserFixture('#applications');
  assert.equal(createVerificationLinkStore(browser).getSnapshot(), null);
  assert.equal(browser.location.hash, '#applications');
});
