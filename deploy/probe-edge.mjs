// Anonymous, opt-in transport checks. Never sends an email, password, or complete reset body.
// Run: node deploy/probe-edge.mjs --origin https://staging.example --deadline-ms 6000
// Run only against a deployment you operate. This does not certify proxy trust or readiness.
import http from 'node:http';
import https from 'node:https';
import { pathToFileURL } from 'node:url';

function transport(origin) { return origin.protocol === 'https:' ? https : http; }

function get(origin, path, timeoutMs) {
  return new Promise((resolve, reject) => {
    const request = transport(origin).get(new URL(path, origin), { agent: false }, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 65536) request.destroy(new Error('oversized probe response'));
      });
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body }));
    });
    const timer = setTimeout(() => request.destroy(new Error('probe deadline')), timeoutMs);
    request.on('close', () => clearTimeout(timer));
    request.on('error', reject);
  });
}

// A late byte followed by a stall distinguishes an elapsed deadline from inactivity alone.
// At most two requests run sequentially, each below the server's 4 KiB limit.
export function slowBody(origin, csrf, cookie, deadlineMs, lateByte = false) {
  return new Promise(resolve => {
    let done = false;
    let opened = false;
    let lateByteWritten = false;
    let began = 0;
    let timer;
    let byteTimer;
    const finish = (ok, detail) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      clearTimeout(byteTimer);
      request.destroy();
      resolve({ ok, detail: detail + (lateByte
        ? `; late byte ${lateByteWritten ? 'written' : 'not written; total-deadline check inconclusive'}` : '') });
    };
    const request = transport(origin).request(new URL('/api/auth/password-reset/request', origin), {
      method: 'POST',
      agent: false,
      headers: {
        'Content-Type': 'application/json', 'Content-Length': '4096',
        Connection: 'close', Cookie: cookie, [csrf.headerName]: csrf.token,
      },
    }, response => {
      const elapsed = Math.round(performance.now() - began);
      // A fast 403/429/503 proves neither body handling nor the configured deadline.
      finish(response.statusCode === 408 && elapsed <= deadlineMs && (!lateByte || lateByteWritten),
        `HTTP ${response.statusCode} after ${elapsed} ms; expected 408 within ${deadlineMs} ms`);
    });
    request.on('socket', socket => {
      const ready = () => {
        opened = true;
        began = performance.now();
        clearTimeout(timer);
        timer = setTimeout(() => finish(false, `no server cutoff within ${deadlineMs} ms`), deadlineMs);
        request.write('{');
        // Send before Tomcat's five-second inactivity cutoff, even with a larger probe budget.
        if (lateByte) byteTimer = setTimeout(() => request.write(' ', error => {
          if (!error) lateByteWritten = true;
        }), Math.min(4000, Math.floor(deadlineMs * 0.75)));
      };
      socket.once(origin.protocol === 'https:' ? 'secureConnect' : 'connect', ready);
    });
    request.on('error', error => {
      // A connection close may be an edge cutoff or a network fault; keep it inconclusive.
      const elapsed = opened ? Math.round(performance.now() - began) : 0;
      finish(false,
        opened ? `connection closed after ${elapsed} ms (${error.code ?? 'transport error'})`
          : 'connection or TLS handshake failed');
    });
    timer = setTimeout(() => finish(false, 'connection or TLS handshake deadline'), deadlineMs);
    request.flushHeaders();
  });
}

export async function probe(origin, { deadlineMs = 6000 } = {}) {
  const results = [];
  const add = (name, ok, detail) => results.push({ name, ok, detail });
  const secure = origin.protocol === 'https:';
  add('HTTPS transport', secure, secure ? 'certificate verification enabled' : 'loopback HTTP is local evidence only');
  let csrf;
  let cookie;
  for (const path of ['/', '/api/auth/csrf']) {
    try {
      const response = await get(origin, path, deadlineMs);
      add(`${path} status`, response.status === 200, `HTTP ${response.status}; redirects are not followed`);
      add(`${path} private caching`, /(?:^|,)\s*no-store\s*(?:,|$)/i.test(response.headers['cache-control'] ?? ''), 'requires Cache-Control: no-store');
      add(`${path} referrer policy`, response.headers['referrer-policy'] === 'no-referrer', 'requires Referrer-Policy: no-referrer');
      if (secure) add(`${path} HSTS`, /(?:^|;)\s*max-age=[1-9]\d*/i.test(response.headers['strict-transport-security'] ?? ''), 'requires positive HSTS max-age');
      if (path === '/api/auth/csrf' && response.status === 200) {
        const candidate = JSON.parse(response.body);
        const cookies = response.headers['set-cookie'] ?? [];
        const session = cookies.find(value => /^JSESSIONID=/i.test(value));
        const valid = typeof candidate.token === 'string' && candidate.token.length > 0
          && candidate.headerName === 'X-CSRF-TOKEN' && session;
        add('anonymous CSRF session', Boolean(valid), 'session values are retained in memory and never printed');
        if (valid) {
          csrf = candidate;
          cookie = session.split(';', 1)[0];
          add('session cookie flags', /;\s*HttpOnly(?:;|$)/i.test(session)
            && /;\s*SameSite=Lax(?:;|$)/i.test(session) && (!secure || /;\s*Secure(?:;|$)/i.test(session)),
          secure ? 'requires Secure, HttpOnly and SameSite=Lax' : 'requires HttpOnly and SameSite=Lax');
        }
      }
    } catch {
      add(`${path} reachable`, false, 'network, TLS, response format, or response deadline failed; no response content logged');
    }
  }
  if (csrf && cookie) {
    for (const lateByte of [false, true]) {
      const result = await slowBody(origin, csrf, cookie, deadlineMs, lateByte);
      add(lateByte ? 'total body deadline after late byte' : 'stalled body deadline', result.ok, result.detail);
    }
  } else add('body deadline checks', false, 'requires a valid anonymous CSRF session');
  return results;
}

export function parseArguments(args) {
  if (args.length === 1 && args[0] === '--help') return null;
  let origin;
  let deadlineMs = 6000;
  for (let index = 0; index < args.length; index += 2) {
    if (args[index] === '--origin') origin = new URL(args[index + 1]);
    else if (args[index] === '--deadline-ms') deadlineMs = Number(args[index + 1]);
    else throw new Error('unsupported argument');
  }
  if (!origin || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') throw new Error('provide a bare origin');
  if (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && ['127.0.0.1', '[::1]', 'localhost'].includes(origin.hostname))) throw new Error('HTTPS required except loopback');
  if (!Number.isInteger(deadlineMs) || deadlineMs < 1000 || deadlineMs > 15000) throw new Error('deadline must be 1000..15000 ms');
  return { origin, deadlineMs };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const options = parseArguments(process.argv.slice(2));
    if (!options) console.log('Usage: node deploy/probe-edge.mjs --origin https://staging.example [--deadline-ms 6000]\nSends two incomplete anonymous reset requests; run only on a deployment you operate. No mail or reset is issued.\nLocal loopback HTTP is allowed, but reports HTTPS as BLOCKED. Exit 1 means a check is blocked.\nProxy trust, capacity, database/worker readiness and delivery require separate operator evidence.');
    else {
      const results = await probe(options.origin, options);
      for (const result of results) console.log(`${result.ok ? 'PASS' : 'BLOCKED'} ${result.name}: ${result.detail}`);
      console.log('BLOCKED remaining release gates: proxy trust/direct-access isolation, load/admission, database/worker readiness, mail delivery, browser behavior, restore/failover (not measured by this probe)');
      process.exitCode = results.some(result => !result.ok) ? 1 : 0;
    }
  } catch {
    console.error('BLOCKED invalid arguments; run with --help. Supplied values are not logged.');
    process.exitCode = 1;
  }
}
