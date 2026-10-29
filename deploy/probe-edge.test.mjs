import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { test } from 'node:test';
import { parseArguments, probe, slowBody } from './probe-edge.mjs';

async function serverFor(t, onReset) {
  const server = createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Referrer-Policy', 'no-referrer');
    if (request.url === '/api/auth/csrf') {
      response.setHeader('Set-Cookie', 'JSESSIONID=synthetic-session; HttpOnly; SameSite=Lax');
      response.end(JSON.stringify({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }));
    } else if (request.url === '/') response.end('<html></html>');
    else {
      assert.equal(request.headers['x-csrf-token'], 'synthetic-csrf');
      assert.equal(request.headers.cookie, 'JSESSIONID=synthetic-session');
      assert.equal(request.headers['content-length'], '4096');
      let body = '';
      request.on('data', chunk => { body += chunk; });
      request.on('close', () => assert.ok(!body.includes('email') && !body.includes('password')));
      onReset(request, response);
    }
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return new URL(`http://127.0.0.1:${server.address().port}`);
}

test('arguments reject credentials, remote cleartext, non-origins and unbounded deadlines', () => {
  for (const args of [[], ['--origin', 'https://user:secret@example.test'],
    ['--origin', 'http://example.test'], ['--origin', 'https://example.test/path'],
    ['--origin', 'https://example.test?secret=x'], ['--origin', 'https://example.test', '--deadline-ms', '0'],
    ['--origin', 'https://example.test', '--deadline-ms', '16000'], ['--origin']]) {
    assert.throws(() => parseArguments(args));
  }
  assert.equal(parseArguments(['--help']), null);
  assert.equal(parseArguments(['--origin', 'https://example.test']).deadlineMs, 6000);
  assert.equal(parseArguments(['--origin', 'http://127.0.0.1:8080']).origin.port, '8080');
});

test('probe records local HTTP limitation and exercises both incomplete bodies without printing credentials', async t => {
  let attempts = 0;
  const bodies = [];
  const origin = await serverFor(t, (request, response) => {
    attempts++;
    let body = '';
    request.on('data', chunk => { body += chunk; });
    const timer = setTimeout(() => { bodies.push(body); response.writeHead(408); response.end(); }, 850);
    response.on('close', () => clearTimeout(timer));
  });
  const results = await probe(origin, { deadlineMs: 1000 });
  assert.equal(attempts, 2);
  assert.deepEqual(bodies, ['{', '{ ']);
  assert.match(results.find(result => result.name === 'total body deadline after late byte').detail, /late byte written/);
  assert.deepEqual(results.filter(result => !result.ok).map(result => result.name), ['HTTPS transport']);
  assert.ok(!JSON.stringify(results).includes('synthetic-csrf'));
  assert.ok(!JSON.stringify(results).includes('synthetic-session'));
});

test('capacity rejection does not establish a body deadline', async t => {
  const origin = await serverFor(t, (_request, response) => { response.writeHead(503); response.end(); });
  const result = await slowBody(origin, { token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }, 'JSESSIONID=synthetic-session', 500);
  assert.equal(result.ok, false);
  assert.match(result.detail, /HTTP 503/);
});

test('client watchdog failure cannot be mistaken for a server deadline', async t => {
  const origin = await serverFor(t, () => {});
  const began = performance.now();
  const result = await slowBody(origin, { token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }, 'JSESSIONID=synthetic-session', 500, true);
  assert.equal(result.ok, false);
  assert.match(result.detail, /no server cutoff/);
  assert.ok(performance.now() - began < 1500);
});

test('cutoff before the late byte is inconclusive for total-deadline evidence', async t => {
  const origin = await serverFor(t, (_request, response) => {
    response.writeHead(408);
    response.end();
  });
  const result = await slowBody(origin, { token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }, 'JSESSIONID=synthetic-session', 500, true);
  assert.equal(result.ok, false);
  assert.match(result.detail, /late byte not written; total-deadline check inconclusive/);
});
