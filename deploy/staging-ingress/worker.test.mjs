import assert from 'node:assert/strict';
import test from 'node:test';
import worker from './worker.mjs';

const site = 'https://campus.example.test';
const env = { ORIGIN_KEY: 'fixture-for-the-in-memory-origin-test' };
const preview = '/api/application-imports/preview';
const commit = '/api/application-imports/fixture/commit';

test('forwards browser multipart bytes and server-owned forwarding headers to the fixed origin', async t => {
  const data = new FormData();
  data.set('file', new Blob(['company,role\r\nExample,Intern\r\n']), 'example.csv');
  const request = new Request(site + preview, { method: 'POST', body: data, headers: {
    'cf-connecting-ip': '192.0.2.1', 'x-forwarded-host': 'fixture.invalid',
    'x-campus-origin-key': 'fixture', 'x-csrf-token': 'fixture-csrf',
  } });
  const original = new Uint8Array(await request.clone().arrayBuffer());
  const log = t.mock.method(console, 'info', () => {});
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url.origin, 'https://campus-internship-tracker-staging.onrender.com');
    assert.equal(url.pathname, preview);
    assert.equal(init.headers.get('x-forwarded-host'), 'campus.example.test');
    assert.equal(init.headers.get('x-forwarded-for'), '192.0.2.1');
    assert.equal(init.headers.get('x-forwarded-proto'), 'https');
    assert.equal(init.headers.get('x-campus-origin-key'), env.ORIGIN_KEY);
    assert.equal(init.headers.get('x-csrf-token'), 'fixture-csrf');
    assert.equal(init.headers.get('content-type'), request.headers.get('content-type'));
    assert.deepEqual(init.body, original);
    assert.equal(init.redirect, 'manual');
    return new Response(null, { status: 201, headers: { 'rndr-id': 'abc12345-1234-1234' } });
  });
  const response = await worker.fetch(request, env);
  assert.equal(response.status, 201);
  const receipt = log.mock.calls[0].arguments[0];
  assert.equal(receipt.id, response.headers.get('x-campus-ingress-id'));
  assert.equal(receipt.originId, 'abc12345-1234-1234');
});

test('the edge rejects a body over each route cap before contacting the origin', async t => {
  const origin = t.mock.method(globalThis, 'fetch');
  const log = t.mock.method(console, 'info', () => {});
  for (const [path, bytes] of [[preview, 3 * 1024 * 1024], [commit, 4096], ['/api/auth/password-reset/request', 4096]]) {
    const response = await worker.fetch(new Request(site + path, {
      method: 'POST', body: new Uint8Array(bytes + 1),
    }), env);
    assert.equal(response.status, 413);
    assert.equal(log.mock.calls.at(-1).arguments[0].originAttempted, false);
    assert.equal(log.mock.calls.at(-1).arguments[0].activeAfterRelease, 0);
  }
  assert.equal(origin.mock.callCount(), 0);
});

test('each import deadline expires from admission and releases capacity for the next upload', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  const origin = t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 201 }));
  const log = t.mock.method(console, 'info', () => {});
  for (const path of [preview, commit]) {
    let source;
    const body = new ReadableStream({ start(controller) { source = controller; } });
    const request = new Request(site + path, { method: 'POST', body, duplex: 'half' });
    const pending = worker.fetch(request, env);
    t.mock.timers.tick(29_000);
    if (path === commit) source.enqueue(new Uint8Array([1]));
    await Promise.resolve();
    t.mock.timers.tick(1000);
    assert.equal((await pending).status, 408);
    const expired = log.mock.calls.at(-1).arguments[0];
    assert.equal(expired.released - expired.started, 30_000);
    assert.equal(expired.originAttempted, false);
    assert.equal(expired.activeAfterRelease, 0);
    assert.equal((await worker.fetch(new Request(site + path, { method: 'POST', body: '{}' }), env)).status, 201);
    assert.equal(log.mock.calls.at(-1).arguments[0].admitted, expired.released);
  }
  assert.equal(origin.mock.callCount(), 2);
});
