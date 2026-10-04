import type { ThemePreference } from './theme.ts';

export interface ThemeSnapshot {
  preference: ThemePreference;
  syncState: 'local' | 'loading' | 'synced' | 'saving' | 'error';
  syncError: string | null;
  canEdit: boolean;
}
interface Dependencies {
  initialPreference: ThemePreference;
  anonymousPreference: () => ThemePreference;
  read: (isCurrent: () => boolean) => Promise<ThemePreference>;
  write: (value: ThemePreference, isCurrent: () => boolean) => Promise<ThemePreference>;
  cache: (value: ThemePreference, anonymous: boolean) => void;
  notify: () => void;
}

// Own async ordering outside React so account changes and network races are independently testable.
export function createThemeController(deps: Dependencies) {
  let account: string | null | undefined;
  let generation = 0, sequence = 0, reading = false, loaded = false, dirty = false;
  let snapshot: ThemeSnapshot = {preference: deps.initialPreference, syncState: 'loading', syncError: null, canEdit: false};
  const listeners = new Set<() => void>();
  const publish = (patch: Partial<ThemeSnapshot>) => {
    snapshot = {...snapshot, ...patch}; listeners.forEach(listener => listener());
  };
  const valid = (g: number, s: number) => generation === g && sequence === s;
  async function refresh() {
    if (!account || reading || dirty || snapshot.syncState === 'saving') return;
    reading = true;
    const g = generation, s = ++sequence;
    if (!loaded) publish({syncState: 'loading', syncError: null, canEdit: false});
    try {
      const preference = await deps.read(() => valid(g, s));
      if (!valid(g, s)) return;
      loaded = true;
      deps.cache(preference, false);
      publish({preference, syncState: 'synced', syncError: null, canEdit: true});
    } catch {
      if (valid(g, s) && !loaded) publish({syncState: 'error', syncError: 'Could not load your account appearance.', canEdit: false});
    } finally { if (valid(g, s)) reading = false; }
  }
  async function save(preference: ThemePreference) {
    const g = generation, s = ++sequence;
    reading = false; dirty = true;
    publish({preference, syncState: 'saving', syncError: null, canEdit: false});
    try {
      const confirmed = await deps.write(preference, () => valid(g, s));
      if (!valid(g, s)) return;
      dirty = false; deps.cache(confirmed, false); deps.notify();
      publish({preference: confirmed, syncState: 'synced', syncError: null, canEdit: true});
    } catch {
      if (valid(g, s)) publish({syncState: 'error', syncError: 'Appearance changed here; account sync failed.', canEdit: true});
    }
  }
  function bindAccount(next: string | null | undefined) {
    if (account === next) return;
    account = next; generation++; sequence++; reading = false; loaded = false; dirty = false;
    if (next === null) {
      const preference = deps.anonymousPreference(); deps.cache(preference, true);
      publish({preference, syncState: 'local', syncError: null, canEdit: true});
    } else {
      publish({syncState: 'loading', syncError: null, canEdit: false});
      if (next) void refresh();
    }
  }
  return {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    bindAccount,
    refresh,
    setPreference(preference: ThemePreference) {
      if (!snapshot.canEdit) return;
      if (account) void save(preference);
      else { deps.cache(preference, true); publish({preference, syncState: 'local', syncError: null}); }
    },
    retry() { if (dirty && account && snapshot.syncState !== 'saving') void save(snapshot.preference); else void refresh(); },
    anonymousChanged() {
      if (account !== null) return;
      const preference = deps.anonymousPreference(); deps.cache(preference, false); publish({preference});
    },
    dispose() { generation++; sequence++; account = undefined; reading = false; listeners.clear(); },
  };
}
