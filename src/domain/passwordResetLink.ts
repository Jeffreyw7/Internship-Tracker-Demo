export interface PasswordResetLink { token: string | null; completed?: boolean }
type LinkBrowser = Pick<Window, 'location' | 'history' | 'addEventListener' | 'removeEventListener'>;
const resetPendingKey = 'campus:password-reset-pending';

// A 32-byte unpadded base64url value has two unused bits in its final character.
export function isPasswordResetToken(value: string): boolean {
  return /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(value);
}

// Persist only a reload hint; the captured secret remains exclusively in memory.
export function createPasswordResetLinkStore(browser: LinkBrowser) {
  let snapshot: PasswordResetLink | null = browser.history.state?.[resetPendingKey] === true ? {token: null} : null;
  const listeners = new Set<() => void>();
  const notify = () => listeners.forEach(listener => listener());
  function clearReloadHint() {
    if (browser.history.state?.[resetPendingKey] !== true) return;
    const state = {...browser.history.state};
    delete state[resetPendingKey];
    browser.history.replaceState(state, '');
  }
  function capture() {
    const { hash, pathname, search } = browser.location;
    if (!hash.startsWith('#reset-password=')) return;
    const token = hash.slice('#reset-password='.length);
    browser.history.replaceState({...browser.history.state, [resetPendingKey]: true}, '', pathname + search);
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
    complete() { clearReloadHint(); snapshot = {token: null, completed: true}; notify(); },
    dismiss() { clearReloadHint(); snapshot = null; notify(); },
  };
}

export const passwordResetLinks = typeof window === 'undefined' ? null : createPasswordResetLinkStore(window);
import { verificationLinks } from './verificationLink.ts';


type AuthenticationLinkStore = Pick<ReturnType<typeof createPasswordResetLinkStore>, 'getSnapshot' | 'subscribe' | 'dismiss'>;

// A newly opened authentication link replaces the previous recovery view and secret.
export function connectAuthenticationLinks(reset: AuthenticationLinkStore, verification: AuthenticationLinkStore) {
  const resetOpened=() => { if(reset.getSnapshot()) verification.dismiss(); };
  const verificationOpened=() => { if(verification.getSnapshot()) reset.dismiss(); };
  // Verification may already have scrubbed a fresh link before the reset store restores its reload hint.
  if(verification.getSnapshot()) reset.dismiss();
  const unsubscribeReset=reset.subscribe(resetOpened);
  const unsubscribeVerification=verification.subscribe(verificationOpened);
  if(reset.getSnapshot()) resetOpened(); else verificationOpened();
  return () => {unsubscribeReset();unsubscribeVerification();};
}

if(passwordResetLinks && verificationLinks) connectAuthenticationLinks(passwordResetLinks,verificationLinks);
