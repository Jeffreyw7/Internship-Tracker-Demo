// Real bundled HTTP/SMTP/restart verification using only Node's standard library.
import assert from 'node:assert/strict';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const jar = path.join(root, 'backend/target/campus-tracker-2.0.0.jar');
assert.ok(fs.existsSync(jar), 'Package with-ui first.');
// Oracle's Windows PATH shim launches another process. Use java.home's real executable so stop/restart stops the JVM.
const javaSettings=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8',windowsHide:true});
const javaHome=javaSettings.stderr.match(/java\.home\s*=\s*(.+)/)?.[1]?.trim();
assert.ok(javaHome,'Cannot locate the Java runtime.');
const java=path.join(javaHome,'bin',globalThis.process.platform==='win32'?'java.exe':'java');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'campus-password-smoke-'));
const messages = [];
const sockets = new Set();
const smtp = net.createServer(socket => {
  sockets.add(socket); socket.on('close', () => sockets.delete(socket));
  socket.write('220 test SMTP\r\n');
  let pending = '', data = false, message = '';
  socket.on('data', bytes => {
    pending += bytes.toString();
    let end;
    while ((end = pending.indexOf('\r\n')) !== -1) {
      const line = pending.slice(0, end); pending = pending.slice(end + 2);
      if (data) {
        if (line === '.') {
          messages.push(message);
          // --serve exposes only synthetic loopback test links for real browser flow verification.
          if (globalThis.process.argv.includes('--serve')) {
            const match = decodeMail(message).match(/#password-(reset|change)=([A-Za-z0-9_-]{43})/);
            if (match) {
              fs.mkdirSync(path.join(root, 'test-results'), { recursive: true });
              fs.writeFileSync(path.join(root, 'test-results/password-mail.json'), JSON.stringify({ purpose: match[1], token: match[2] }));
            }
          }
          message = ''; data = false; socket.write('250 accepted\r\n');
        }
        else message += line.replace(/^\.\./, '.') + '\r\n';
      } else if (/^(EHLO|HELO)/i.test(line)) socket.write('250 localhost\r\n');
      else if (/^DATA/i.test(line)) { data = true; socket.write('354 send data\r\n'); }
      else if (/^QUIT/i.test(line)) socket.end('221 goodbye\r\n');
      else socket.write('250 OK\r\n');
    }
  });
});
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
await listen(smtp);
const probe = net.createServer(); await listen(probe);
const port = probe.address().port; await new Promise(resolve => probe.close(resolve));
const base = `http://127.0.0.1:${port}`;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
class Client {
  cookie = '';
  async call(url, method = 'GET', body, expected = 200, headers = {}) {
    if (body !== undefined) {
      if (body instanceof URLSearchParams) headers = { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' };
      else { headers = { ...headers, 'Content-Type': 'application/json' }; body = JSON.stringify(body); }
    }
    const response = await fetch(base + url, { method, body, headers: { ...headers, Cookie: this.cookie }, signal: AbortSignal.timeout(10000) });
    for (const header of response.headers.getSetCookie()) {
      const match = header.match(/JSESSIONID=([^;]*)/);
      if (match) this.cookie = match[1] ? `JSESSIONID=${match[1]}` : '';
    }
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${url}: unexpected status`);
    return response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : text;
  }
  async csrf() { const token = await this.call('/api/auth/csrf'); return { [token.headerName]: token.token }; }
  async write(url, body, expected = 200) { return this.call(url, 'POST', body, expected, await this.csrf()); }
  async login(email, password, expected = 200) { return this.write('/api/auth/login', new URLSearchParams({ email, password }), expected); }
}
let process;
async function stop() {
  if (!process || process.exitCode !== null) return;
  const exited = new Promise(resolve => process.once('exit', resolve)); process.kill(); await exited;
}
async function start(workerEnabled = true) {
  const args = ['-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1',
    `--spring.datasource.url=jdbc:h2:file:${temp.replaceAll('\\', '/')}/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
    '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false',
    '--campus.password.mail-enabled=true', `--campus.password.worker-enabled=${workerEnabled}`, '--campus.password.worker-delay=100', `--campus.password.public-origin=${base}`,
    '--campus.password.mail-from=campus@example.test', '--campus.password.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    '--campus.password.rate-limit-secret=synthetic-test-secret-at-least-32-bytes', '--campus.password.limits.email-minute=100',
    '--campus.password.limits.request-minute=100', '--spring.mail.host=127.0.0.1', `--spring.mail.port=${smtp.address().port}`,
    '--spring.mail.properties.mail.smtp.auth=false', '--spring.mail.properties.mail.smtp.starttls.enable=false',
    '--spring.mail.properties.mail.smtp.starttls.required=false'];
  process = spawn(java, args, { cwd: temp, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  process.stdout.on('data', bytes => fs.appendFileSync(path.join(temp, 'server.log'), bytes));
  process.stderr.on('data', bytes => fs.appendFileSync(path.join(temp, 'server.log'), bytes));
  for (let i = 0; i < 120; i++) {
    if (process.exitCode !== null) throw new Error('Smoke server exited; see ' + path.join(temp, 'server.log'));
    try { if ((await new Client().call('/api/health')).status === 'UP') return; } catch {}
    await pause(250);
  }
  throw new Error('Smoke server startup timed out.');
}
function decodeMail(raw) {
  const split = raw.indexOf('\r\n\r\n');
  const headers = raw.slice(0, split), body = raw.slice(split + 4);
  return /Content-Transfer-Encoding:\s*quoted-printable/i.test(headers)
    ? body.replace(/=\r?\n/g, '').replace(/=([0-9A-F]{2})/gi, (_, code) => String.fromCharCode(parseInt(code, 16)))
    : /Content-Transfer-Encoding:\s*base64/i.test(headers) ? Buffer.from(body, 'base64').toString() : body;
}
async function token(purpose) {
  for (let i = 0; i < 60; i++) {
    for (const raw of messages) {
      const decoded = decodeMail(raw);
      const match = decoded.match(new RegExp(`#password-${purpose}=([A-Za-z0-9_-]{43})`));
      if (match) { messages.splice(messages.indexOf(raw), 1); return match[1]; }
    }
    await pause(100);
  }
  throw new Error('Password email did not arrive.');
}
try {
  await start();
  const client = new Client(), other = new Client();
  const email = 'http-password@example.test', oldPassword = 'Temporary-test-123!', newPassword = 'Recovered-test-456!';
  const page = await client.call('/'); assert.ok(page.includes('id="root"'));
  for (const match of page.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)) assert.ok((await client.call(match[1])).length > 100);
  await client.call('/api/auth/password-reset/request', 'POST', { email }, 403);
  await client.write('/api/auth/register', { email, password: oldPassword, displayName: 'Password Tester', includeSamples: true }, 201);
  await client.login(email, oldPassword); await other.login(email, oldPassword);
  assert.equal((await client.call('/api/applications')).totalElements, 10);
  const accepted = await client.write('/api/auth/password-reset/request', { email }, 202);
  const unknown = await client.write('/api/auth/password-reset/request', { email: 'unknown@example.test' }, 202);
  assert.deepEqual(accepted, unknown);
  const reset = await token('reset');
  await client.write('/api/auth/password-reset/confirm', { token: reset, newPassword: 'weak' }, 400);
  await client.write('/api/auth/password-reset/confirm', { token: reset, newPassword });
  await client.call('/api/auth/me', 'GET', undefined, 401); await other.call('/api/auth/me', 'GET', undefined, 401);
  await client.write('/api/auth/password-reset/confirm', { token: reset, newPassword }, 400);
  await client.login(email, oldPassword, 401); await client.login(email, newPassword);
  // Queue the next link with delivery disabled, then recover that encrypted job after a real JVM restart.
  await stop(); await start(false);
  await client.write('/api/auth/password-reset/request', { email }, 202);
  await stop(); await start();
  const persisted = await token('reset');
  await client.call('/api/auth/me', 'GET', undefined, 401);
  const recovered = 'Restart-test-789!';
  await client.write('/api/auth/password-reset/confirm', { token: persisted, newPassword: recovered });
  await client.login(email, recovered); await other.login(email, recovered);
  assert.equal((await client.call('/api/applications')).totalElements, 10);
  await client.write('/api/auth/password-change/request', { currentPassword: 'Wrong-password-123!' }, 400);
  await client.write('/api/auth/password-change/request', { currentPassword: recovered }, 202);
  const change = { token: await token('change'), newPassword: 'Changed-test-890!' };
  await other.write('/api/auth/password-change/confirm', change, 400);
  await client.write('/api/auth/password-change/confirm', change);
  await other.call('/api/auth/me', 'GET', undefined, 401); await client.call('/api/auth/me', 'GET', undefined, 401);
  await client.login(email, recovered, 401); await client.login(email, change.newPassword);
  assert.equal((await client.call('/api/applications')).totalElements, 10);
  console.log('PASS: bundled UI/assets, real SMTP, CSRF, response parity, policy, replay, two-session revocation, restart recovery, email-confirmed change, preserved application data.');
  if (globalThis.process.argv.includes('--serve')) {
    fs.mkdirSync(path.join(root, 'test-results'), { recursive: true });
    fs.writeFileSync(path.join(root, 'test-results/password-browser.json'), JSON.stringify({ base, email, password: change.newPassword }));
    console.log('Browser verification server: ' + base);
    await new Promise(resolve => { globalThis.process.once('SIGTERM', resolve); globalThis.process.once('SIGINT', resolve); });
  }
} catch (error) {
  await stop();
  fs.mkdirSync(path.join(root, 'test-results'), { recursive: true });
  fs.copyFileSync(path.join(temp, 'server.log'), path.join(root, 'test-results/password-server-failure.log'));
  if (fs.existsSync(path.join(temp, 'campus.mv.db'))) fs.copyFileSync(path.join(temp, 'campus.mv.db'), path.join(root, 'test-results/password-failure.mv.db'));
  throw error;
} finally {
  await stop(); for (const socket of sockets) socket.destroy(); await new Promise(resolve => smtp.close(resolve));
  // Only remove the unique temporary directory created by this script.
  fs.rmSync(temp, { recursive: true, force: true });
}
