/** Bounded LOCAL capacity probe, not the production-equivalent 30-minute SLO gate.
 * node tests/reset-readiness-load.mjs (requires the existing with-ui JAR and Java 17+)
 * Self-starts only loopback HTTP/SMTP, uses a disposable H2 database, sends no external mail.
 * Four sequential intake samples (known/unknown/suppressed) then 24 same-IP requests
 * at concurrency eight. The intentionally tiny sample never establishes percentiles
 * for production, campus NAT capacity, PostgreSQL locks or mailbox delivery.
 */
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const jar = path.join(root, 'backend/target/campus-tracker-2.0.0.jar');
assert.ok(fs.existsSync(jar), 'Build the with-ui JAR first.');
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'campus-reset-readiness-'));
const logPath = path.join(temp, 'server.log');
const log = fs.openSync(logPath, 'a');
const sockets = new Set();
const messages = [];
const smtp = net.createServer(socket => {
  sockets.add(socket);
  socket.on('close', () => sockets.delete(socket));
  socket.setEncoding('utf8');
  socket.write('220 localhost readiness capture\r\n');
  let input = '', data = false, message = '';
  socket.on('data', chunk => {
    input += chunk;
    let end;
    while ((end = input.indexOf('\r\n')) !== -1) {
      const line = input.slice(0, end);
      input = input.slice(end + 2);
      if (data) {
        if (line === '.') {
          messages.push(message); data = false; message = ''; socket.write('250 accepted\r\n');
        } else message += (line.startsWith('..') ? line.slice(1) : line) + '\r\n';
      } else if (/^(EHLO|HELO)/i.test(line)) socket.write('250-localhost\r\n250 SIZE 1048576\r\n');
      else if (line.toUpperCase() === 'DATA') { data = true; socket.write('354 end with dot\r\n'); }
      else if (line.toUpperCase() === 'QUIT') socket.end('221 bye\r\n');
      else socket.write('250 ok\r\n');
    }
  });
  // An aborted SMTP test connection must not crash the harness or leak its child.
  socket.on('error', () => {});
});
const cookies = new Map();
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let child, base;

function call(route, { method = 'GET', data, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const body = data === undefined ? undefined : JSON.stringify(data);
    const start = performance.now();
    const request = http.request(base + route, { method, timeout: 6000, headers: {
      ...headers, ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }),
      Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; '),
    } }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('error', reject);
      response.on('end', () => {
        for (const cookie of response.headers['set-cookie'] ?? []) {
          const pair = cookie.split(';')[0], separator = pair.indexOf('=');
          cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
        }
        const text = Buffer.concat(chunks).toString('utf8');
        try {
          resolve({ status: response.statusCode, headers: response.headers, body: text ? JSON.parse(text) : null,
            milliseconds: performance.now() - start });
        } catch { reject(new Error(`Non-JSON response: ${route}`)); }
      });
    });
    request.on('timeout', () => request.destroy(new Error(`Timed out: ${route}`)));
    request.on('error', reject);
    request.end(body);
  });
}

async function csrf() {
  const response = await call('/api/auth/csrf');
  assert.equal(response.status, 200);
  return { [response.body.headerName]: response.body.token };
}
async function stop() {
  if (!child?.pid || child.exitCode !== null) return;
  const exited = new Promise(resolve => child.once('exit', resolve));
  if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  else child.kill('SIGTERM');
  const timeout = setTimeout(() => child.kill('SIGKILL'), 10000);
  try { await exited; } finally { clearTimeout(timeout); }
}
function summary(samples) {
  const ordered = samples.map(value => value.milliseconds).sort((a, b) => a - b);
  const percentile = fraction => Math.round(ordered[Math.ceil(ordered.length * fraction) - 1] * 10) / 10;
  return { count: ordered.length, p50_ms: percentile(.5), p95_ms: percentile(.95), p99_ms: percentile(.99),
    statuses: samples.reduce((counts, value) => ({ ...counts, [value.status]: (counts[value.status] ?? 0) + 1 }), {}) };
}
async function waitForMessage(pattern) {
  for (let attempt = 0; attempt < 150; attempt++) {
    const match = messages.join('\n').match(pattern);
    if (match) return match;
    await sleep(100);
  }
  throw new Error('Synthetic SMTP acceptance did not complete within 15 seconds.');
}

try {
  await new Promise((resolve, reject) => smtp.once('error', reject).listen(0, '127.0.0.1', resolve));
  const reserve = net.createServer();
  await new Promise((resolve, reject) => reserve.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = reserve.address().port;
  await new Promise(resolve => reserve.close(resolve));
  base = `http://127.0.0.1:${port}`;
  child = spawn(java, ['-jar', jar, '--spring.profiles.active=local', `--server.port=${port}`, '--server.address=127.0.0.1',
    `--spring.datasource.url=jdbc:h2:file:${path.join(temp, 'campus').replaceAll('\\', '/')};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
    '--spring.datasource.username=sa', '--spring.datasource.password=',
    '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false',
    '--spring.mail.host=127.0.0.1', `--spring.mail.port=${smtp.address().port}`,
    '--spring.mail.username=', '--spring.mail.password=', '--spring.mail.properties.mail.smtp.auth=false',
    '--spring.mail.properties.mail.smtp.starttls.enable=false',
    '--spring.mail.properties.mail.smtp.starttls.required=false',
    `--campus.mail.public-origin=${base}`, '--campus.mail.worker-enabled=true',
    '--campus.reset.development-enabled=true'], { cwd: temp, stdio: ['ignore', log, log] });
  let spawnError;
  child.on('error', error => { spawnError = error; });
  let started = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error('Java exited during readiness probe startup.');
    try { if ((await call('/api/health')).body.status === 'UP') { started = true; break; } } catch {}
    await sleep(500);
  }
  assert.ok(started, 'Java startup deadline exceeded.');
  const identity = { email: 'readiness-known@example.test', password: 'Private readiness passphrase' };
  const registered = await call('/api/auth/register', { method: 'POST', headers: await csrf(),
    data: { ...identity, displayName: 'Synthetic readiness', includeSamples: false } });
  assert.equal(registered.status, 202);
  const headers = await csrf();
  const admitted = [];
  for (const email of [identity.email, 'readiness-unknown@example.test', identity.email, 'readiness-other@example.test']) {
    const response = await call('/api/auth/password-reset/request', { method: 'POST', headers, data: { email } });
    assert.equal(response.status, 202);
    assert.equal(response.body.status, 'PASSWORD_RESET_REQUEST_ACCEPTED');
    assert.ok(String(response.headers['cache-control']).toLowerCase().split(',').map(value => value.trim()).includes('no-store'));
    if (admitted.length) assert.deepEqual(response.body, admitted[0].body, 'Known, unknown and recipient-suppressed responses must match.');
    admitted.push(response);
  }
  const link = await waitForMessage(/#reset-password=([A-Za-z0-9_-]{43})/);
  const completed = await call('/api/auth/password-reset/confirm', { method: 'POST', headers,
    data: { token: link[1], newPassword: 'Changed readiness private passphrase' } });
  assert.equal(completed.status, 200);
  assert.equal(completed.body.status, 'PASSWORD_RESET_COMPLETE');
  await waitForMessage(/Your password was changed/);
  const burst = [];
  let next = 0;
  await Promise.all(Array.from({ length: 8 }, async () => {
    while (next < 24) {
      const index = next++;
      burst.push(await call('/api/auth/password-reset/request', { method: 'POST', headers,
        data: { email: `readiness-burst-${index}@example.test` } }));
    }
  }));
  for (const response of burst) {
    assert.ok([202, 429].includes(response.status), `Unexpected burst status ${response.status}`);
    if (response.status === 429) assert.match(String(response.headers['retry-after']), /^[1-9]\d*$/);
    else assert.deepEqual(response.body, admitted[0].body);
  }
  assert.ok(burst.some(value => value.status === 429), 'The bounded same-IP burst must reach admission throttling.');
  await stop();
  const logs = fs.readFileSync(logPath, 'utf8');
  for (const secret of [identity.email, identity.password, 'Changed readiness private passphrase', link[1], 'readiness-unknown@example.test']) {
    assert.ok(!logs.includes(secret), 'Application logs must omit synthetic addresses, passwords and reset secrets.');
  }
  console.log(JSON.stringify({ scope: 'LOCAL_H2_LOOPBACK_ONLY', concurrency: 8, burst_requests: 24,
    admitted_mix: '1 known + 2 unknown + 1 recipient-suppressed', intake: summary(admitted),
    completion: summary([completed]), same_ip_burst: summary(burst), smtp: 'reset link and password-changed notice accepted',
    limits: 'Tiny sample; no production SLO, PostgreSQL, NAT, sustained-load or mailbox-delivery claim.' }, null, 2));
} catch (error) {
  // Logs contain no expected secrets, but do not echo them on an unexpected failure.
  console.error(error.message);
  process.exitCode = 1;
} finally {
  await stop();
  fs.closeSync(log);
  for (const socket of sockets) socket.destroy();
  if (smtp.listening) await new Promise(resolve => smtp.close(resolve));
  fs.rmSync(temp, { recursive: true, force: true });
}
