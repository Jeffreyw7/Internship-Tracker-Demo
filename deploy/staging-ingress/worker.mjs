const ORIGIN = 'https://campus-internship-tracker-staging.onrender.com';
const PREVIEW = { name: 'preview', bytes: 3 * 1024 * 1024, sizeCode: 'IMPORT_REQUEST_LIMIT' };
const CONFIRMATION = { name: 'confirmation', bytes: 4096, sizeCode: 'IMPORT_SIZE_LIMIT' };
const RESET = { name: 'reset', bytes: 4096 };
const UPLOAD_MS = 30_000;
const MAX_ACTIVE = 4;
const PASSTHROUGH = [
  'accept', 'accept-language', 'authorization', 'cache-control', 'content-encoding', 'content-type',
  'cookie', 'if-match', 'if-modified-since', 'if-none-match', 'if-range', 'if-unmodified-since',
  'origin', 'range', 'user-agent', 'x-campus-account', 'x-csrf-token',
];
let active = 0;
let instance;

function policyFor(method, pathname) {
  if (method !== 'POST') return;
  const path = decodeURIComponent(pathname).split('/').map(part => part.split(';')[0]).join('/');
  if (path === '/api/application-imports/preview') return PREVIEW;
  if (/^\/api\/application-imports\/[^/]+\/commit$/.test(path)) return CONFIRMATION;
  if (path.startsWith('/api/auth/password-reset/')) return RESET;
}

class Rejection extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function reject(response) {
  return Response.json({ message: response.message, code: response.code }, {
    status: response.status,
    headers: {
      'cache-control': 'no-store', 'referrer-policy': 'no-referrer',
      'strict-transport-security': 'max-age=31536000; includeSubDomains',
    },
  });
}

function outboundHeaders(request, url, token) {
  const result = new Headers();
  for (const name of PASSTHROUGH) {
    const value = request.headers.get(name);
    if (value !== null) result.set(name, value);
  }
  result.set('x-forwarded-host', url.host);
  result.set('x-forwarded-proto', 'https');
  result.set('x-forwarded-port', '443');
  const client = request.headers.get('cf-connecting-ip');
  if (client) result.set('x-forwarded-for', client);
  result.set('x-campus-origin-key', token);
  return result;
}

async function readBody(request, policy, started) {
  const tooLarge = () => new Rejection(413, 'The request exceeds the upload limit.', policy.sizeCode);
  const timeout = () => {
    const error = new Rejection(408, 'The upload deadline elapsed.',
      policy === RESET ? undefined : 'IMPORT_UPLOAD_TIMEOUT');
    error.expiredAt = Date.now();
    return error;
  };
  const length = request.headers.get('content-length');
  if (length !== null && !/^\d+$/.test(length)) throw new Rejection(400, 'Invalid request length.');
  if (length !== null && Number(length) > policy.bytes) throw tooLarge();
  if (!request.body) {
    if (Number(length) > 0) throw new Rejection(400, 'Incomplete request body.');
    return new Uint8Array();
  }

  const buffer = new Uint8Array(length === null ? policy.bytes : Number(length));
  const deadline = started + UPLOAD_MS;
  const reader = request.body.getReader();
  let timer;
  async function collect() {
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (Date.now() >= deadline) throw timeout();
      if (done) {
        if (length !== null && size !== buffer.length) throw new Rejection(400, 'Incomplete request body.');
        return buffer.subarray(0, size);
      }
      if (size + value.length > policy.bytes) throw tooLarge();
      if (size + value.length > buffer.length) throw new Rejection(400, 'Invalid request length.');
      buffer.set(value, size);
      size += value.length;
    }
  }
  try {
    const expiry = new Promise((_, fail) => {
      timer = setTimeout(() => fail(timeout()), Math.max(0, deadline - Date.now()));
    });
    return await Promise.race([collect(), expiry]);
  } catch (error) {
    void reader.cancel().catch(() => {});
    throw error;
  } finally {
    clearTimeout(timer);
    reader.releaseLock();
  }
}

async function forward(request, url, token, body) {
  const upstream = new URL(ORIGIN);
  upstream.pathname = url.pathname;
  upstream.search = url.search;
  const response = await fetch(upstream, {
    method: request.method, headers: outboundHeaders(request, url, token),
    body, redirect: 'manual', cache: 'no-store', signal: request.signal,
  });
  const outgoing = new Response(response.body, response);
  const location = outgoing.headers.get('location');
  if (location) {
    const target = new URL(location, upstream);
    if (target.origin === ORIGIN) outgoing.headers.set('location', url.origin + target.pathname + target.search + target.hash);
  }
  return outgoing;
}

export default {
  async fetch(request, env) {
    const started = Date.now();
    const url = new URL(request.url);
    if (url.protocol !== 'https:') {
      url.protocol = 'https:';
      return Response.redirect(url, 301);
    }
    if (!env.ORIGIN_KEY || env.ORIGIN_KEY.length < 32) {
      return reject(new Rejection(503, 'Staging ingress is unavailable.'));
    }
    let policy;
    try {
      policy = policyFor(request.method, url.pathname);
    } catch {
      return reject(new Rejection(400, 'Invalid request path.'));
    }
    if (!policy) return forward(request, url, env.ORIGIN_KEY, request.body);
    if (active >= MAX_ACTIVE) return reject(new Rejection(503, 'Upload capacity is occupied.'));

    active++;
    instance ??= crypto.randomUUID();
    const id = crypto.randomUUID();
    const receipt = { event: 'campus.ingress', instance, id, route: policy.name, limit: policy.bytes,
      started, admitted: Date.now(), originAttempted: false };
    try {
      const body = await readBody(request, policy, started);
      receipt.bodyFinished = Date.now();
      receipt.bytes = body.byteLength;
      receipt.originAttempted = true;
      const response = await forward(request, url, env.ORIGIN_KEY, body);
      const originId = response.headers.get('rndr-id');
      if (originId && /^[0-9a-f-]{12,40}$/i.test(originId)) receipt.originId = originId;
      receipt.status = response.status;
      response.headers.set('x-campus-ingress-id', id);
      return response;
    } catch (error) {
      const failure = error instanceof Rejection ? error : new Rejection(502, 'The request could not complete.');
      receipt.status = failure.status;
      if (failure.expiredAt !== undefined) receipt.expiredAt = failure.expiredAt;
      const response = reject(failure);
      response.headers.set('x-campus-ingress-id', id);
      return response;
    } finally {
      active--;
      console.info({ ...receipt, released: Date.now(), activeAfterRelease: active });
    }
  },
};
