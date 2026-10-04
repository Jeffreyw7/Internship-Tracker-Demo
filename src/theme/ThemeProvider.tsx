import { createContext, useContext, useEffect, useLayoutEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { preferencesApi } from '../data/preferencesApi';
import { createThemeController } from './themeController';
import { ANONYMOUS_KEY, BOOTSTRAP_KEY, INVALIDATION_KEY, applyTheme, browserStorage, readPreference, resolveTheme, writePreference } from './theme';

const ThemeContext = createContext<ReturnType<typeof useThemeValue> | null>(null);
function useThemeValue() {
  const [controller] = useState(() => createThemeController({
    initialPreference: readPreference(browserStorage(), BOOTSTRAP_KEY),
    anonymousPreference: () => readPreference(browserStorage(), ANONYMOUS_KEY),
    read: preferencesApi.read, write: preferencesApi.write,
    cache(preference, anonymous) {
      writePreference(browserStorage(), BOOTSTRAP_KEY, preference);
      if (anonymous) writePreference(browserStorage(), ANONYMOUS_KEY, preference);
    },
    notify() { try { browserStorage()?.setItem(INVALIDATION_KEY, crypto.randomUUID()); } catch { /* Focus/polling still synchronize. */ } },
  }));
  const snapshot = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const [darkOS, setDarkOS] = useState(() => typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  useLayoutEffect(() => { applyTheme(snapshot.preference, darkOS); }, [snapshot.preference, darkOS]);
  useEffect(() => {
    const media = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : undefined;
    const mediaChanged = () => setDarkOS(media?.matches ?? false);
    const refresh = () => { if (document.visibilityState === 'visible') void controller.refresh(); };
    const storageChanged = (event: StorageEvent) => {
      if (event.storageArea !== browserStorage()) return;
      if (event.key === INVALIDATION_KEY || event.key === null) refresh();
      if (event.key === ANONYMOUS_KEY || event.key === null) controller.anonymousChanged();
    };
    mediaChanged(); media?.addEventListener('change', mediaChanged);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    window.addEventListener('storage', storageChanged);
    const timer = window.setInterval(refresh, 60_000);
    return () => {
      media?.removeEventListener('change', mediaChanged);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
      window.removeEventListener('storage', storageChanged);
      window.clearInterval(timer);
    };
  }, [controller]);
  return {...snapshot, resolvedTheme: resolveTheme(snapshot.preference, darkOS),
    setPreference: controller.setPreference, retrySync: controller.retry, bindAccount: controller.bindAccount};
}
export function ThemeProvider({children}: {children: ReactNode}) {
  const value = useThemeValue();
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
export function useTheme() {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error('useTheme requires ThemeProvider');
  return theme;
}
