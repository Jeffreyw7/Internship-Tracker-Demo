export interface PasswordLink { purpose: 'reset' | 'change'; token: string | null }
export function readPasswordLink(location: Pick<Location, 'hash' | 'pathname' | 'search'>, history: Pick<History, 'replaceState'>): PasswordLink | null {
  const params = new URLSearchParams(location.hash.slice(1));
  const reset = params.has('password-reset');
  const change = params.has('password-change');
  if (!reset && !change) return null;
  history.replaceState(null, '', location.pathname + location.search);
  const purpose = reset ? 'reset' : 'change';
  const values = params.getAll('password-' + purpose);
  const token = reset !== change && values.length === 1 && /^[A-Za-z0-9_-]{43}$/.test(values[0]) ? values[0] : null;
  return { purpose, token };
}
// Capture before session lookup; initialization survives StrictMode's remount checks.
export let initialPasswordLink = typeof window === 'undefined' ? null : readPasswordLink(window.location, window.history);
export function clearInitialPasswordLink(): void { initialPasswordLink = null; }
