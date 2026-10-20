/** Real HTTP, SMTP, and restart-persistence smoke test for the bundled Spring Boot JAR. */
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const jar = path.join(root, 'backend', 'target', 'campus-tracker-2.0.0.jar');
assert.ok(fs.existsSync(jar), 'Build the with-ui JAR before running this test.');

function javaExecutable() {
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const probe = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
  assert.equal(probe.status, 0, `Java executable not found: ${probe.stderr}`);
  const home = /^\s*java\.home\s*=\s*(.+)$/m.exec(probe.stderr)?.[1]?.trim();
  return home ? path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
}

const reserve = net.createServer();
await new Promise((resolve, reject) => reserve.listen(0, '127.0.0.1', resolve).once('error', reject));
const port = reserve.address().port;
await new Promise(resolve => reserve.close(resolve));
const base = `http://127.0.0.1:${port}`;

const messages = [];
const smtpSockets = new Set();
const smtp = net.createServer(socket => {
  smtpSockets.add(socket);
  socket.on('close', () => smtpSockets.delete(socket));
  socket.setEncoding('utf8');
  socket.write('220 localhost Campus smoke capture\r\n');
  let input = '', dataMode = false, message = '';
  socket.on('data', chunk => {
    input += chunk;
    let boundary;
    while ((boundary = input.indexOf('\r\n')) >= 0) {
      let line = input.slice(0, boundary);
      input = input.slice(boundary + 2);
      if (dataMode) {
        if (line === '.') {
          messages.push(message);
          message = ''; dataMode = false; socket.write('250 queued\r\n');
        } else message += (line.startsWith('..') ? line.slice(1) : line) + '\r\n';
        continue;
      }
      const command = line.toUpperCase();
      if (command.startsWith('EHLO') || command.startsWith('HELO')) socket.write('250-localhost\r\n250 SIZE 1048576\r\n');
      else if (command.startsWith('MAIL FROM:') || command.startsWith('RCPT TO:')) socket.write('250 ok\r\n');
      else if (command === 'DATA') { dataMode = true; socket.write('354 end with dot\r\n'); }
      else if (command === 'QUIT') { socket.end('221 bye\r\n'); return; }
      else socket.write('250 ok\r\n');
    }
  });
});
await new Promise((resolve, reject) => smtp.listen(0, '127.0.0.1', resolve).once('error', reject));
const smtpPort = smtp.address().port;

const cookies = new Map();
function rememberCookies(headers) {
  for (const value of headers['set-cookie'] ?? []) {
    const [pair] = value.split(';', 1);
    const separator = pair.indexOf('=');
    cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
  }
}

function call(route, { method = 'GET', data, rawBody, headers = {}, expected = 200, form = false } = {}) {
  return new Promise((resolve, reject) => {
    let body;
    if (rawBody !== undefined) body = rawBody;
    else if (data !== undefined) {
      body = form ? new URLSearchParams(data).toString() : JSON.stringify(data);
      headers = { ...headers, 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json' };
    }
    if (cookies.size) headers = { ...headers, Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; ') };
    if (body !== undefined) headers['Content-Length'] = Buffer.byteLength(body);
    const request = http.request(base + route, { method, headers, timeout: 5000 }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => {
        rememberCookies(response.headers);
        const raw = Buffer.concat(chunks);
        if (response.statusCode !== expected) {
          reject(new Error(`${method} ${route}: expected ${expected}, got ${response.statusCode}: ${raw.toString().slice(0, 300)}`));
          return;
        }
        resolve(String(response.headers['content-type'] ?? '').includes('application/json') && raw.length
          ? JSON.parse(raw.toString('utf8')) : raw);
      });
    });
    request.on('timeout', () => request.destroy(new Error(`${method} ${route} timed out`)));
    request.on('error', reject);
    if (body !== undefined) request.write(body);
    request.end();
  });
}

async function importPreview(csv, options, headers, expected = 201) {
  const boundary = 'campus-smoke-boundary';
  const parts = [
    `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.csv"\r\nContent-Type: text/csv\r\n\r\n${csv}\r\n`,
    `--${boundary}\r\nContent-Disposition: form-data; name="options"\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(options)}\r\n`,
    `--${boundary}--\r\n`,
  ];
  return call('/api/application-imports/preview', { method: 'POST', rawBody: Buffer.from(parts.join('')),
    headers: { ...headers, 'Content-Type': `multipart/form-data; boundary=${boundary}` }, expected });
}

function importCommit(preview, headers, expected = 200) {
  return call(`/api/application-imports/${preview.id}/commit`, { method: 'POST', headers, expected,
    data: { reviewDigest: preview.reviewDigest, acknowledgement: { existingApplicationsToUpdate: preview.counts.updated } } });
}

async function csrf() {
  const token = await call('/api/auth/csrf');
  return { [token.headerName]: token.token };
}

async function nativePreview(origin, csv, options, headers) {
  const form = new FormData();
  form.append('file', new Blob([csv], { type: 'text/csv' }), 'applications.csv');
  form.append('options', new Blob([JSON.stringify(options)], { type: 'application/json' }));
  const response = await fetch(origin + '/api/application-imports/preview', {
    method: 'POST', headers: { ...headers, Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; ') }, body: form,
  });
  const body = await response.json();
  assert.equal(response.status, 201, JSON.stringify(body));
  return body;
}

// Deliberately discard the response body after dispatch; only GET may establish the outcome.
async function loseCommitResponse(preview, headers) {
  const body = JSON.stringify({ reviewDigest: preview.reviewDigest, acknowledgement: { existingApplicationsToUpdate: preview.counts.updated } });
  await new Promise((resolve, reject) => {
    const request = http.request(`${base}/api/application-imports/${preview.id}/commit`, {
      method: 'POST', headers: { ...headers, Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; '),
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, response => { response.destroy(); resolve(); });
    request.once('error', reject);
    request.end(body);
  });
}

async function verificationToken() {
  for (let attempt = 0; attempt < 100; attempt++) {
    const match = messages.join('\n').match(/#verify-email=([A-Za-z0-9_-]{43})/);
    if (match) return match[1];
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Verification message was not accepted by the SMTP capture server.');
}

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'campus-http-test-'));
const logPath = path.join(temp, 'server.log');
const log = fs.openSync(logPath, 'a');
const database = path.join(temp, 'campus').replaceAll('\\', '/');
const command = [
  '-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1',
  // Windows shutdown is abrupt; make the isolated test database durable before terminating Java.
  `--spring.datasource.url=jdbc:h2:file:${database};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
  '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false',
  '--spring.mail.host=127.0.0.1', `--spring.mail.port=${smtpPort}`,
  `--campus.mail.public-origin=${base}`, '--campus.mail.worker-enabled=true',
  '--campus.verification.rate-limits-enabled=false',
];

async function portIsClosed() {
  return new Promise(resolve => {
    const socket = net.connect(port, '127.0.0.1');
    socket.once('connect', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(true));
  });
}

async function stop(child) {
  if (!child || child.exitCode !== null) return;
  await new Promise(resolve => {
    let timer;
    const done = () => { clearTimeout(timer); resolve(); };
    child.once('exit', done);
    timer = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 20000);
    if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    else child.kill('SIGTERM');
    if (child.exitCode !== null) done();
  });
  for (let attempt = 0; attempt < 50; attempt++) {
    if (await portIsClosed()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Test JVM is still listening on port ${port} after shutdown.`);
}

async function start() {
  const process = spawn(javaExecutable(), command, { cwd: temp, stdio: ['ignore', log, log] });
  for (let attempt = 0; attempt < 90; attempt++) {
    if (process.exitCode !== null) throw new Error(`Java exited during startup:\n${fs.readFileSync(logPath, 'utf8').slice(-3000)}`);
    try {
      if ((await call('/api/health')).status === 'UP') return process;
    } catch { /* Wait for Spring Boot to accept requests. */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  await stop(process);
  throw new Error(`Java startup timed out:\n${fs.readFileSync(logPath, 'utf8').slice(-3000)}`);
}

let server, vite;
try {
  server = await start();
  const page = (await call('/')).toString('utf8');
  assert.ok(page.includes('Campus') && page.includes('id="root"'));
  for (const match of page.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)) assert.ok((await call(match[1])).length > 100);
  await call('/api/applications', { expected: 401 });
  const identity = { email: 'http-test@example.test', password: 'Temporary-test-123!' };
  const accepted = await call('/api/auth/register', { method: 'POST', data: { ...identity, displayName: 'HTTP Tester', includeSamples: true }, headers: await csrf(), expected: 202 });
  assert.equal(accepted.status, 'VERIFICATION_REQUEST_ACCEPTED');
  const pending = await call('/api/auth/login', { method: 'POST', data: identity, headers: await csrf(), expected: 403, form: true });
  assert.equal(pending.code, 'EMAIL_VERIFICATION_REQUIRED');
  const token = await verificationToken();
  const confirmed = await call('/api/auth/email-verification/confirm', { method: 'POST', data: { token, password: identity.password }, headers: await csrf() });
  assert.equal(confirmed.status, 'EMAIL_VERIFIED');
  await call('/api/auth/login', { method: 'POST', data: identity, headers: await csrf(), form: true });
  assert.equal((await call('/api/applications')).totalElements, 10);
  assert.deepEqual(await call('/api/me/preferences'), { themePreference: 'system' });
  let headers = await csrf();
  await call('/api/me/preferences', { method: 'PUT', data: { themePreference: 'dark' }, expected: 403 });
  assert.deepEqual(await call('/api/me/preferences', { method: 'PUT', data: { themePreference: 'dark' }, headers }), { themePreference: 'dark' });
  await call('/api/me/preferences', { method: 'PUT', data: { themePreference: 'sepia' }, headers, expected: 400 });

  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const draft = { company: 'Restart verification', role: 'Java Intern', location: 'Remote', status: 'Applied', appliedDate: yesterday, deadline: null, followUpDate: null, notes: 'Saved through real HTTP', stages: [] };
  const application = await call('/api/applications', { method: 'POST', data: draft, headers, expected: 201 });
  const applicationPath = `/api/applications/${application.id}`;
  draft.notes = 'Survives a complete JVM restart';
  const updated = await call(applicationPath, { method: 'PUT', data: draft, headers: { ...headers, 'If-Match': String(application.version) } });
  assert.ok(updated.version > application.version);
  await call(applicationPath, { method: 'PUT', data: draft, headers: { ...headers, 'If-Match': String(application.version) }, expected: 409 });
  const deleted = await call(applicationPath, { method: 'DELETE', headers: { ...headers, 'If-Match': String(updated.version) } });
  await call(applicationPath, { expected: 404 });
  const restored = await call(applicationPath + '/restore', { method: 'POST', headers: { ...headers, 'If-Match': String(deleted.version) } });
  assert.equal(restored.notes, draft.notes);

  // Use real multipart bytes so CSRF, transport limits and strict parsing run through the embedded container.
  const csv = '\ufeffCompany,Role,Notes\r\n"CSV, Company",CSV Intern,"=literal\nsecond line"\r\n';
  const options = { schemaVersion: 1, mapping: { 0: 'company', 1: 'role', 2: 'notes' }, dateFormat: 'ISO', rows: [] };
  await importPreview(csv, options, {}, 403);
  const preview = await importPreview(csv, options, headers);
  assert.equal(preview.committable, true);
  assert.equal(preview.counts.added, 1);
  await importCommit(preview, {}, 403);
  await call(`/api/application-imports/${preview.id}/commit`, { method: 'POST', headers, expected: 422,
    data: { reviewDigest: preview.reviewDigest, acknowledgement: { existingApplicationsToUpdate: 1 } } });
  const receipt = await importCommit(preview, headers);
  assert.equal(receipt.counts.added, 1);
  assert.deepEqual(await importCommit(preview, headers), receipt);
  assert.deepEqual((await call(`/api/application-imports/${preview.id}`)).receipt, receipt);
  const csvApplication = await call(`/api/applications/${receipt.rows[0].applicationId}`);
  assert.equal(csvApplication.company, 'CSV, Company');
  assert.equal(csvApplication.notes, '=literal\nsecond line');

  const updateCsv = `Application ID,Company,Notes\n${restored.id},,CSV updated\n`;
  const updateOptions = { schemaVersion: 1, mapping: { 0: 'applicationId', 1: 'company', 2: 'notes' }, dateFormat: 'ISO', rows: [] };
  const updatePreview = await importPreview(updateCsv, updateOptions, headers);
  assert.equal(updatePreview.counts.updated, 1);
  assert.equal(updatePreview.rows[0].after.company, restored.company);
  const updateReceipt = await importCommit(updatePreview, headers);
  assert.equal(updateReceipt.counts.updated, 1);
  assert.equal((await call(applicationPath)).notes, 'CSV updated');

  const conflictingCsv = `Application ID,Company,Role,Notes\n,Must roll back,Intern,\n${restored.id},,,Import change\n`;
  const conflictingOptions = { schemaVersion: 1, mapping: { 0: 'applicationId', 1: 'company', 2: 'role', 3: 'notes' }, dateFormat: 'ISO', rows: [] };
  const conflictingPreview = await importPreview(conflictingCsv, conflictingOptions, headers);
  const beforeConflict = await call(applicationPath);
  await call(applicationPath, { method: 'PUT', headers: { ...headers, 'If-Match': String(beforeConflict.version) }, data: { ...draft, notes: 'Concurrent edit retained' } });
  const conflict = await importCommit(conflictingPreview, headers, 409);
  assert.equal(conflict.code, 'IMPORT_TARGET_CHANGED');
  assert.equal((await call('/api/applications')).totalElements, 12, 'Failed batch cannot leave the Add row behind.');
  const nativeOptions = { ...options, mapping: { 0: 'company', 1: 'role' } };
  const native = await nativePreview(base, 'Company,Role\nNative multipart,Intern\n', nativeOptions, headers);
  const { createServer } = await import('vite');
  vite = await createServer({ root, logLevel: 'warn', optimizeDeps: { noDiscovery: true, include: [] },
    server: { host: '127.0.0.1', port: 0, proxy: { '/api': { target: base } } } });
  await vite.listen();
  const proxyOrigin = `http://127.0.0.1:${vite.httpServer.address().port}`;
  const proxied = await nativePreview(proxyOrigin, 'Company,Role\nNative multipart,Intern\n', { ...nativeOptions, supersedesPreviewId: native.id }, headers);
  assert.equal((await call(`/api/application-imports/${native.id}`)).status, 'SUPERSEDED');
  await loseCommitResponse(proxied, headers);
  const recoveredReceipt = (await call(`/api/application-imports/${proxied.id}`)).receipt;
  assert.equal(recoveredReceipt.counts.added, 1, 'GET recovers completion without replaying the lost commit.');
  await vite.close(); vite = undefined;
  const restartPreview = await importPreview('Company,Role\nAfter restart,Intern\n', { ...options, mapping: { 0: 'company', 1: 'role' } }, headers);
  await stop(server); server = undefined;

  cookies.clear();
  server = await start();
  await call('/api/auth/me', { expected: 401 });
  await call('/api/auth/login', { method: 'POST', data: identity, headers: await csrf(), form: true });
  assert.deepEqual(await call('/api/me/preferences'), { themePreference: 'dark' });
  assert.equal((await call(applicationPath)).notes, 'Concurrent edit retained');
  assert.deepEqual((await call(`/api/application-imports/${preview.id}`)).receipt, receipt, 'Original receipt survives JVM restart.');
  assert.deepEqual(await importCommit(preview, await csrf()), receipt, 'Duplicate commit after restart does not repeat writes.');
  assert.equal((await call(`/api/application-imports/${restartPreview.id}`)).status, 'PREVIEW');
  assert.equal((await importCommit(restartPreview, await csrf())).counts.added, 1);
  assert.deepEqual((await call(`/api/application-imports/${proxied.id}`)).receipt, recoveredReceipt);
  assert.equal((await call('/api/applications')).totalElements, 14);
  await call('/api/application-imports/00000000-0000-0000-0000-000000000000/commit', { method: 'POST', expected: 404, headers: await csrf(),
    data: { reviewDigest: 'missing', acknowledgement: { existingApplicationsToUpdate: 0 } } });
  await call('/api/auth/logout', { method: 'POST', headers: await csrf(), expected: 204 });
  await call('/api/applications', { expected: 401 });
  console.log('PASS: real HTTP/assets, verification/authentication, preferences, CRUD/undo, multipart CSV add/update/preservation, native FormData bundled/Vite proxy, CSRF, atomic conflict, duplicate receipts, lost-response GET reconciliation, and application/import JVM restart recovery.');
} catch (error) {
  console.error(error);
  if (fs.existsSync(logPath)) console.error(fs.readFileSync(logPath, 'utf8').slice(-5000));
  process.exitCode = 1;
} finally {
  await vite?.close();
  await stop(server).catch(error => { console.error(error); process.exitCode = 1; });
  fs.closeSync(log);
  for (const socket of smtpSockets) socket.destroy();
  await new Promise(resolve => smtp.close(resolve));
  fs.rmSync(temp, { recursive: true, force: true });
}
