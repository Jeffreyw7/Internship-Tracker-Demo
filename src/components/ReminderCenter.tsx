import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Bell, CalendarDays, Clock3, X } from 'lucide-react';
import { ApiError } from '../data/api';
import { reminderApi, type Reminder, type ReminderOverview, type ReminderPreferences } from '../data/reminderApi';
import { formatReminderTime } from '../domain/reminderTime';

const categoryNames = { INTERVIEW: 'Interview', DEADLINE: 'Deadline', FOLLOW_UP: 'Follow-up' };
function eventDate(date: string) {
  return new Date(date + 'T12:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

export default function ReminderCenter({ onClose, onOpenApplication }: { onClose: () => void; onOpenApplication: (id: string) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [data, setData] = useState<ReminderOverview | null>(null);
  const [draft, setDraft] = useState<ReminderPreferences | null>(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const busyRef = useRef(false);
  const alive = useRef(true);
  const epoch = useRef(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [snoozeMinutes, setSnoozeMinutes] = useState(60);
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const load = useCallback(async (force = false) => {
    if (busyRef.current && !force) return;
    const requestEpoch = ++epoch.current;
    try {
      const next = await reminderApi.overview();
      if (!alive.current || requestEpoch !== epoch.current) return;
      setData(next);
      if (!dirtyRef.current) setDraft(next.preferences);
      setError('');
    } catch (e) {
      if (alive.current && requestEpoch === epoch.current) setError(e instanceof Error ? e.message : 'Could not load reminders.');
    }
  }, []);
  useEffect(() => {
    alive.current = true;
    dialog.current?.showModal();
    void load();
    const timer = setInterval(() => { void load(); }, 30000);
    const focus = () => { void load(); };
    window.addEventListener('focus', focus);
    return () => { alive.current = false; epoch.current++; clearInterval(timer); window.removeEventListener('focus', focus); dialog.current?.close(); };
  }, [load]);
  function edit<K extends keyof ReminderPreferences>(key: K, value: ReminderPreferences[K]) {
    setDraft(p => p ? { ...p, [key]: value } : p);dirtyRef.current = true;setDirty(true);setMessage('');
  }
  async function save(event: FormEvent) {
    event.preventDefault();if (!draft || busyRef.current) return;
    busyRef.current = true;setBusy(true);epoch.current++;setError('');setMessage('');
    try {
      const next = await reminderApi.savePreferences(draft);
      if (alive.current) { setData(next);setDraft(next.preferences);dirtyRef.current = false;setDirty(false);setMessage('Reminder settings saved.'); }
    } catch (e) { if (alive.current) setError(e instanceof Error ? e.message : 'Could not save reminder settings.'); }
    finally { busyRef.current = false;if (alive.current) setBusy(false); }
  }
  async function act(item: Reminder, action: 'snooze' | 'dismiss') {
    if (busyRef.current) return;
    busyRef.current = true;setBusy(true);epoch.current++;setError('');setMessage('');
    try {
      if (action === 'snooze') await reminderApi.snooze(item, snoozeMinutes);else await reminderApi.dismiss(item);
      await load(true);
      if (alive.current) setMessage(action === 'snooze' ? 'Reminder snoozed.' : 'Reminder dismissed.');
    } catch (e) {
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) await load(true);
      if (alive.current) setError(e instanceof Error ? e.message : 'Could not update the reminder.');
    } finally { busyRef.current = false;if (alive.current) setBusy(false); }
  }
  function reloadSettings() {
    dirtyRef.current = false;setDirty(false);if (data) setDraft(data.preferences);void load();
  }
  const ready = data?.reminders.filter(r => r.state === 'READY') ?? [];
  const pending = data?.reminders.filter(r => r.state === 'PENDING') ?? [];
  function items(list: Reminder[]) {
    return list.map(item => <li className="reminder-item" key={item.id}>
      <div className="reminder-item-icon">{item.kind === 'INTERVIEW' ? <CalendarDays size={18} /> : <Clock3 size={18} />}</div>
      <div className="reminder-item-content"><span className="reminder-kind">{categoryNames[item.kind]}{item.snoozed ? ' · Snoozed' : ''}</span>
        <button className="reminder-application" disabled={busy} onClick={() => onOpenApplication(item.applicationId)}>{item.company} · {item.role}</button>
        <p>{item.label} · {eventDate(item.eventDate)}</p>
        <small>{item.state === 'READY' ? 'Ready since ' : 'Eligible from '}{formatReminderTime(item.dueAt, data!.preferences.timezone)}</small>
        {data!.preferences.deliveryMode === 'DAILY_DIGEST' && item.state === 'PENDING' && <small>Joins the next daily digest at {data!.preferences.digestTime}.</small>}
        {item.emailStatus !== 'NONE' && <small className={item.emailStatus === 'FAILED' ? 'reminder-mail-failed' : ''}>Email: {item.emailStatus === 'FAILED' ? 'Delivery failed; reminder remains available here' : item.emailStatus.toLowerCase()}</small>}
      </div>
      <div className="reminder-item-actions"><button className="button secondary" disabled={busy} onClick={() => void act(item, 'snooze')}>Snooze</button>
        <button className="text-button" disabled={busy} onClick={() => void act(item, 'dismiss')}>Dismiss</button></div>
    </li>);
  }
  return <dialog ref={dialog} className="reminder-dialog" aria-labelledby="reminder-title" onCancel={e => { e.preventDefault();if (!busyRef.current) onClose(); }}>
    <header className="reminder-header"><div><span className="eyebrow"><Bell size={15} /> YOUR NEXT STEPS</span><h2 id="reminder-title">Automated reminders</h2></div>
      <button className="icon-button" aria-label="Close reminders" disabled={busy} onClick={onClose}><X size={20} /></button></header>
    <div className="reminder-body">
      {error && <div className="error-box" role="alert">{error} <button className="text-button" disabled={busy} onClick={() => void load()}>Refresh</button></div>}
      {message && <p className="reminder-message" role="status">{message}</p>}
      {!data || !draft ? <p role="status">{error ? 'Use Refresh to try again.' : 'Loading reminders…'}</p> : <>
        <section className="reminder-settings" aria-label="Reminder settings"><form className="application-form" onSubmit={save}>
          <fieldset disabled={busy}><legend>Make room for what matters.</legend><p>Choose when reminders arrive. Interview dates use your chosen reminder time.</p>
            <label className="reminder-check"><input type="checkbox" checked={draft.enabled} onChange={e => edit('enabled', e.target.checked)} /> Enable automated reminders</label>
            <div className="reminder-category-grid">{([
              ['interviewsEnabled', 'interviewLeadDays', 'Interviews'], ['deadlinesEnabled', 'deadlineLeadDays', 'Saved application deadlines'], ['followUpsEnabled', 'followUpLeadDays', 'Follow-ups'],
            ] as const).map(([enabled, lead, label]) => <div className="reminder-category" key={enabled}>
              <label className="reminder-check"><input type="checkbox" checked={draft[enabled]} onChange={e => edit(enabled, e.target.checked)} />{label}</label>
              <label>Days before<input type="number" required min={0} max={30} disabled={!draft[enabled]} value={draft[lead]} onChange={e => edit(lead, e.target.valueAsNumber)} /></label>
            </div>)}</div>
            <div className="reminder-form-grid"><label>Timezone<input required maxLength={100} value={draft.timezone} placeholder="America/New_York" onChange={e => edit('timezone', e.target.value)} /></label>
              <label>Reminder time<input type="time" required value={draft.reminderTime} onChange={e => edit('reminderTime', e.target.value)} /></label>
              <label>Delivery<select value={draft.deliveryMode} onChange={e => edit('deliveryMode', e.target.value as ReminderPreferences['deliveryMode'])}>
                <option value="IMMEDIATE">As each reminder becomes due</option><option value="DAILY_DIGEST">Daily digest</option></select></label>
              {draft.deliveryMode === 'DAILY_DIGEST' && <label>Digest time<input type="time" required value={draft.digestTime} onChange={e => edit('digestTime', e.target.value)} /></label>}
            </div>
            <button type="button" className="text-button reminder-browser-zone" onClick={() => edit('timezone', browserZone)}>Use browser timezone ({browserZone})</button>
            <label className="reminder-check"><input type="checkbox" disabled={!data.emailAvailable && !draft.emailEnabled} checked={draft.emailEnabled} onChange={e => edit('emailEnabled', e.target.checked)} /> Also send to my account email</label>
            <p className="reminder-help">{data.emailAvailable ? 'Email follows the same timing and digest settings.' : 'Email delivery is unavailable on this server. Reminders appear here.'} Sample applications do not generate reminders.</p>
            <div className="reminder-settings-actions"><button className="button primary" disabled={!dirty || busy}>{busy ? 'Saving…' : 'Save settings'}</button>
              {dirty && <button type="button" className="text-button" onClick={reloadSettings}>Reload saved settings</button>}</div>
          </fieldset>
        </form></section>
        <section className="reminder-list" aria-labelledby="ready-reminder-title"><div className="reminder-list-heading"><h3 id="ready-reminder-title">Ready for your attention <span className="count-badge">{data.readyCount}</span></h3>
          <label>Snooze for<select aria-label="Snooze duration" value={snoozeMinutes} disabled={busy} onChange={e => setSnoozeMinutes(Number(e.target.value))}>
            <option value={15}>15 minutes</option><option value={60}>1 hour</option><option value={1440}>1 day</option><option value={10080}>1 week</option></select></label></div>
          {ready.length ? <ul>{items(ready)}</ul> : <p className="reminder-empty">{data.preferences.enabled ? 'You’re caught up. Due reminders will appear here.' : 'Enable reminders and save your settings to get started.'}</p>}
          {pending.length > 0 && <><h3>Coming up</h3><ul>{items(pending)}</ul></>}
          {data.reminders.length === 200 && <p className="reminder-help">Showing the first 200 reminders. Dismiss finished items to see more.</p>}
        </section>
      </>}
    </div>
  </dialog>;
}
