export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';
export const BOOTSTRAP_KEY = 'campus.theme.bootstrap.v1';
export const ANONYMOUS_KEY = 'campus.theme.anonymous.v1';
export const INVALIDATION_KEY = 'campus.theme.changed.v1';
export function normalizePreference(value: unknown): ThemePreference {
  return value === 'dark' || value === 'light' ? value : 'system';
}
export function resolveTheme(preference: ThemePreference, darkOS: boolean): ResolvedTheme {
  return preference === 'system' ? (darkOS ? 'dark' : 'light') : preference;
}
export function readPreference(storage: Pick<Storage, 'getItem'> | undefined, key: string): ThemePreference {
  try { return normalizePreference(storage?.getItem(key)); } catch { return 'system'; }
}
export function writePreference(storage: Pick<Storage, 'setItem'> | undefined, key: string, value: ThemePreference) {
  try { storage?.setItem(key, value); } catch { /* Storage is only a cache; account persistence still works. */ }
}
export function browserStorage(): Storage | undefined {
  try { return window.localStorage; } catch { return undefined; }
}
export function applyTheme(preference: ThemePreference, darkOS: boolean) {
  const resolved = resolveTheme(preference, darkOS);
  document.documentElement.dataset.theme = resolved;
  document.documentElement.dataset.themePreference = preference;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#101815' : '#f8faf9');
}
