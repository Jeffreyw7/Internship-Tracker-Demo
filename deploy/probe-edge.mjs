import http from 'node:http';
import https from 'node:https';
import { pathToFileURL } from 'node:url';

const GET_TIMEOUT_MS = 90_000;

function transport(origin) { return origin.protocol === 'https:' ? https : http; }

function get(origin, path, timeoutMs) {
  return new Promise((resolve, reject) => {
    const request = transport(origin).get(new URL(path, origin), { agent: false, rejectUnauthorized: true }, response => {
      const tlsProtocol = response.socket.authorized ? response.socket.getProtocol() : undefined;
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 65536) request.destroy(new Error('oversized probe response'));
      });
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body, tlsProtocol }));
    });
    const timer = setTimeout(() => request.destroy(new Error('probe deadline')), timeoutMs);
    request.on('close', () => clearTimeout(timer));
    request.on('error', reject);
  });
}

function isLiveSessionCookie(value) {
  if (!/^JSESSIONID=(?:"[^";]+"|[^;"\s]+)(?:;|$)/i.test(value)) return false;
  const maxAge = /;\s*Max-Age=(-?\d+)(?:;|$)/i.exec(value);
  if (maxAge) return Number(maxAge[1]) > 0;
  const expires = /;\s*Expires=([^;]+)/i.exec(value);
  const expiresAt = expires ? Date.parse(expires[1]) : NaN;
  return Number.isNaN(expiresAt) || expiresAt > Date.now();
}

export async function probe(origin, { deadlineMs = GET_TIMEOUT_MS } = {}) {
  const results = [];
  const add = (name, ok, detail) => results.push({ name, ok, detail });
  const secure = origin.protocol === 'https:';
  let negotiatedTls;
  for (const [path, expectedStatus] of [['/', 200], ['/api/auth/csrf', 200], ['/api/auth/me', 401]]) {
    try {
      const response = await get(origin, path, deadlineMs);
      negotiatedTls ??= response.tlsProtocol;
      const requestId = response.headers['rndr-id'];
      const isRenderId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}(?:-[0-9a-f]{4}-[0-9a-f]{12})?$/i.test(requestId ?? '');
      const status = `HTTP ${response.status}, expected ${expectedStatus}`;
      add(`${path} status`, response.status === expectedStatus, isRenderId ? `${status}, Render ID ${requestId}` : status);
      add(`${path} private caching`, /(?:^|,)\s*no-store\s*(?:,|$)/i.test(response.headers['cache-control'] ?? ''), 'requires Cache-Control: no-store');
      if (path !== '/' && (origin.hostname.endsWith('.onrender.com') || response.headers['cf-ray']
          || response.headers['cf-cache-status'] || response.headers.age !== undefined)) {
        const cacheStatus = response.headers['cf-cache-status']?.toUpperCase();
        const bypassed = cacheStatus === 'DYNAMIC' || cacheStatus === 'BYPASS';
        const hasAge = response.headers.age !== undefined;
        add(`${path} edge cache bypass`, bypassed && !hasAge,
          `${bypassed ? cacheStatus : 'bypass unconfirmed'}, Age ${hasAge ? 'present' : 'absent'}`);
      }
      add(`${path} referrer policy`, response.headers['referrer-policy'] === 'no-referrer', 'requires Referrer-Policy: no-referrer');
      if (secure) add(`${path} HSTS`, /(?:^|;)\s*max-age=[1-9]\d*\s*(?:;|$)/i.test(response.headers['strict-transport-security'] ?? ''), 'requires positive HSTS max-age');
      if (path === '/api/auth/csrf' && response.status === 200) {
        const candidate = JSON.parse(response.body);
        const cookies = response.headers['set-cookie'] ?? [];
        const session = cookies.find(isLiveSessionCookie);
        const valid = typeof candidate.token === 'string' && candidate.token.trim().length > 0
          && candidate.headerName === 'X-CSRF-TOKEN' && session;
        add('anonymous CSRF session', Boolean(valid), 'requires a CSRF token and session cookie');
        if (valid) {
          add('session cookie flags', /;\s*HttpOnly(?:;|$)/i.test(session)
            && /;\s*SameSite=Lax(?:;|$)/i.test(session) && (!secure || /;\s*Secure(?:;|$)/i.test(session)),
          secure ? 'requires Secure, HttpOnly and SameSite=Lax' : 'requires HttpOnly and SameSite=Lax');
        }
      }
    } catch {
      add(`${path} reachable`, false, 'network, TLS, response format, or response deadline failed; no response content logged');
    }
  }
  results.unshift({ name: 'HTTPS transport', ok: Boolean(negotiatedTls),
    detail: negotiatedTls ? `verified certificate, negotiated ${negotiatedTls}`
      : secure ? 'no verified TLS response' : 'loopback HTTP is local evidence only' });
  return results;
}

export function parseArguments(args) {
  if (args.length === 1 && args[0] === '--help') return null;
  let origin;
  let deadlineMs = GET_TIMEOUT_MS;
  for (let index = 0; index < args.length; index += 2) {
    if (args[index] === '--origin') origin = new URL(args[index + 1]);
    else if (args[index] === '--deadline-ms') deadlineMs = Number(args[index + 1]);
    else throw new Error('unsupported argument');
  }
  if (!origin || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') throw new Error('provide a bare origin');
  if (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && ['127.0.0.1', '[::1]', 'localhost'].includes(origin.hostname))) throw new Error('HTTPS required except loopback');
  if (!Number.isInteger(deadlineMs) || deadlineMs < 1000 || deadlineMs > GET_TIMEOUT_MS) throw new Error(`client timeout must be 1000..${GET_TIMEOUT_MS} ms`);
  return { origin, deadlineMs };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const options = parseArguments(process.argv.slice(2));
    if (!options) console.log(`Usage: node deploy/probe-edge.mjs --origin https://staging.example [--deadline-ms ${GET_TIMEOUT_MS}]\nChecks anonymous GET responses on /, /api/auth/csrf and /api/auth/me. The deadline is the client timeout for each GET.\nLoopback HTTP reports HTTPS as BLOCKED. Exit 1 means a check is blocked.`);
    else {
      const results = await probe(options.origin, options);
      for (const result of results) console.log(`${result.ok ? 'PASS' : 'BLOCKED'} ${result.name}: ${result.detail}`);
      process.exitCode = results.some(result => !result.ok) ? 1 : 0;
    }
  } catch {
    console.error('BLOCKED invalid arguments; run with --help. Supplied values are not logged.');
    process.exitCode = 1;
  }
}
