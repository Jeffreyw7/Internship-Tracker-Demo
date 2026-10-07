export interface VerificationLink { token: string | null }
type LinkBrowser = Pick<Window, 'location' | 'history' | 'addEventListener' | 'removeEventListener'>;

// Capture secrets outside React rendering so StrictMode cannot consume a link twice.
// Snapshots live only in memory; a refresh requires reopening the email link.
export function createVerificationLinkStore(browser: LinkBrowser) {
  let snapshot: VerificationLink | null = null;
  const listeners = new Set<() => void>();
  function capture() {
    const { hash, pathname, search } = browser.location;
    if (!hash.startsWith('#verify-email=')) return;
    const candidate = hash.slice('#verify-email='.length);
    browser.history.replaceState(browser.history.state, '', pathname + search);
    snapshot = { token: /^[A-Za-z0-9_-]{43}$/.test(candidate) ? candidate : null };
    listeners.forEach(listener => listener());
  }
  capture();
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) {
      listeners.add(listener);
      if (listeners.size === 1) browser.addEventListener('hashchange', capture);
      capture();
      return () => {
        listeners.delete(listener);
        if (!listeners.size) browser.removeEventListener('hashchange', capture);
      };
    },
    dismiss() {
      snapshot = null;
      listeners.forEach(listener => listener());
    },
  };
}

export const verificationLinks = typeof window === 'undefined' ? null : createVerificationLinkStore(window);
