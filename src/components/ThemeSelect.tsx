import { useId } from 'react';
import { Monitor, Moon, Sun } from 'lucide-react';
import { useTheme } from '../theme/ThemeProvider';
import type { ThemePreference } from '../theme/theme';

export function ThemeSelect() {
  const id = useId();
  const {preference, canEdit, syncState, syncError, setPreference, retrySync} = useTheme();
  const Icon = preference === 'system' ? Monitor : preference === 'dark' ? Moon : Sun;
  return <div className="theme-control">
    <label className="theme-select" htmlFor={id}><Icon size={15} aria-hidden="true"/><span className="sr-only">Appearance</span>
      <select id={id} value={preference} disabled={!canEdit} onChange={e => setPreference(e.target.value as ThemePreference)} aria-describedby={id+'-status'}>
        <option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option>
      </select>
    </label>
    <span id={id+'-status'} className={syncError ? 'theme-sync-error' : syncState === 'saving' ? 'theme-saving' : 'sr-only'} role="status">
      {syncError || (syncState === 'saving' ? 'Saving appearance…' : '')}
      {syncError && <button type="button" className="text-button" onClick={retrySync}>Retry</button>}
    </span>
  </div>;
}
