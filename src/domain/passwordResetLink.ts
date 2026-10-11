export interface PasswordResetLink { token: string | null; completed?: boolean }
type LinkBrowser = Pick<Window, 'location' | 'history' | 'addEventListener' | 'removeEventListener'>;

// A 32-byte unpadded base64url value has two unused bits in its final character.
export function isPasswordResetToken(value: string): boolean {
  return /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(value);
}

// Capture and scrub before React renders; no token reaches browser storage.
export function createPasswordResetLinkStore(browser: LinkBrowser) {
  let snapshot: PasswordResetLink | null = null;
  const listeners = new Set<() => void>();
  const notify = () => listeners.forEach(listener => listener());
  function capture() {
    const { hash, pathname, search } = browser.location;
    if (!hash.startsWith('#reset-password=')) return;
    const token = hash.slice('#reset-password='.length);
    browser.history.replaceState(browser.history.state, '', pathname + search);
    snapshot = {token: isPasswordResetToken(token) ? token : null};
    notify();
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
    complete() { snapshot = {token: null, completed: true}; notify(); },
    dismiss() { snapshot = null; notify(); },
  };
}

export const passwordResetLinks = typeof window === 'undefined' ? null : createPasswordResetLinkStore(window);
import { verificationLinks } from './verificationLink.ts';


type AuthenticationLinkStore = Pick<ReturnType<typeof createPasswordResetLinkStore>, 'getSnapshot' | 'subscribe' | 'dismiss'>;

// A newly opened authentication link replaces the previous recovery view and secret.
export function connectAuthenticationLinks(reset: AuthenticationLinkStore, verification: AuthenticationLinkStore) {
  const resetOpened=() => { if(reset.getSnapshot()) verification.dismiss(); };
  const verificationOpened=() => { if(verification.getSnapshot()) reset.dismiss(); };
  const unsubscribeReset=reset.subscribe(resetOpened);
  const unsubscribeVerification=verification.subscribe(verificationOpened);
  if(reset.getSnapshot()) resetOpened(); else verificationOpened();
  return () => {unsubscribeReset();unsubscribeVerification();};
}

if(passwordResetLinks && verificationLinks) connectAuthenticationLinks(passwordResetLinks,verificationLinks);
