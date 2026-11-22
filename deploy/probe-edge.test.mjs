import assert from 'node:assert/strict';
import http, { createServer } from 'node:http';
import https from 'node:https';
import { once } from 'node:events';
import { test } from 'node:test';
import { parseArguments, probe } from './probe-edge.mjs';

async function serverFor(t, adjust = () => {}) {
  const requests = [];
  const server = createServer((request, response) => {
    requests.push([request.method, request.url, request.headers.cookie]);
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Referrer-Policy', 'no-referrer');
    let body = '';
    if (request.url === '/') body = '<html></html>';
    else if (request.url === '/api/auth/csrf') {
      response.setHeader('Set-Cookie', 'JSESSIONID=synthetic-session; HttpOnly; SameSite=Lax');
      body = JSON.stringify({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' });
    } else if (request.url === '/api/auth/me') response.statusCode = 401;
    else response.statusCode = 404;
    adjust(request, response);
    response.end(body);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return { requests, origin: new URL(`http://127.0.0.1:${server.address().port}`) };
}

test('arguments reject credentials, remote cleartext, non-origins and unbounded client timeouts', () => {
  for (const args of [[], ['--origin', 'https://user:secret@example.test'],
    ['--origin', 'http://example.test'], ['--origin', 'https://example.test/path'],
    ['--origin', 'https://example.test?secret=x'], ['--origin', 'https://example.test', '--deadline-ms', '0'],
    ['--origin', 'https://example.test', '--deadline-ms', '90001'], ['--origin']]) {
    assert.throws(() => parseArguments(args));
  }
  assert.equal(parseArguments(['--help']), null);
  assert.equal(parseArguments(['--origin', 'https://example.test']).deadlineMs, 90_000);
  assert.equal(parseArguments(['--origin', 'https://example.test', '--deadline-ms', '6000']).deadlineMs, 6000);
  assert.equal(parseArguments(['--origin', 'http://127.0.0.1:8080']).origin.port, '8080');
});

test('checks three anonymous GETs and keeps session values out of the report', async t => {
  const { origin, requests } = await serverFor(t, (request, response) => {
    response.setHeader('Rndr-Id', request.url === '/' ? '1234abcd-0001-0002' : 'synthetic-session');
  });
  const results = await probe(origin);
  assert.deepEqual(requests, [
    ['GET', '/', undefined],
    ['GET', '/api/auth/csrf', undefined],
    ['GET', '/api/auth/me', undefined],
  ]);
  assert.deepEqual(results.filter(result => !result.ok).map(result => result.name), ['HTTPS transport']);
  assert.match(results.find(result => result.name === '/ status').detail, /1234abcd-0001-0002/);
  assert.doesNotMatch(JSON.stringify(results), /synthetic-csrf|synthetic-session/);
});

test('reports incorrect status and caching on the anonymous account response', async t => {
  const { origin } = await serverFor(t, (request, response) => {
    if (request.url === '/api/auth/me') {
      response.statusCode = 200;
      response.setHeader('Cache-Control', 'public');
      response.setHeader('CF-Cache-Status', 'HIT');
    }
  });
  const results = await probe(origin);
  assert.deepEqual(results.filter(result => !result.ok).map(result => result.name), [
    'HTTPS transport', '/api/auth/me status', '/api/auth/me private caching', '/api/auth/me edge cache bypass',
  ]);
});

test('checks Cloudflare bypass and Age independently of origin Cache-Control', async t => {
  const { origin } = await serverFor(t, (request, response) => {
    if (request.url === '/api/auth/csrf') response.setHeader('CF-Cache-Status', 'DYNAMIC');
    if (request.url === '/api/auth/me') response.setHeader('Age', '12');
  });
  const results = await probe(origin);
  assert.equal(results.find(result => result.name === '/api/auth/csrf edge cache bypass').ok, true);
  assert.equal(results.find(result => result.name === '/api/auth/me edge cache bypass').ok, false);
});

test('a Render check needs verified TLS and explicit cache-bypass evidence', async t => {
  const { origin } = await serverFor(t);
  const verifyPeer = [];
  t.mock.method(https, 'get', (url, options, callback) => {
    verifyPeer.push(options.rejectUnauthorized);
    return http.get(new URL(url.pathname, origin), options, callback);
  });
  const results = await probe(new URL('https://fixture.onrender.com'));
  assert.deepEqual(verifyPeer, [true, true, true]);
  assert.equal(results.find(result => result.name === 'HTTPS transport').ok, false);
  for (const path of ['/api/auth/csrf', '/api/auth/me']) {
    assert.equal(results.find(result => result.name === `${path} edge cache bypass`).ok, false);
  }
});

test('empty and expired cookies do not establish an anonymous session', async t => {
  for (const cookie of ['JSESSIONID=', 'JSESSIONID=""', 'JSESSIONID=old; Max-Age=0',
    'JSESSIONID=old; Expires=Thu, 01 Jan 1970 00:00:00 GMT']) {
    const { origin } = await serverFor(t, (request, response) => {
      if (request.url === '/api/auth/csrf') response.setHeader('Set-Cookie', cookie + '; HttpOnly; SameSite=Lax');
    });
    const results = await probe(origin);
    assert.equal(results.find(result => result.name === 'anonymous CSRF session').ok, false);
  }
});
