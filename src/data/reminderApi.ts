import { jsonBody, request } from './api.ts';

export interface ReminderPreferences {
  enabled: boolean;
  interviewsEnabled: boolean;
  deadlinesEnabled: boolean;
  followUpsEnabled: boolean;
  interviewLeadDays: number;
  deadlineLeadDays: number;
  followUpLeadDays: number;
  timezone: string;
  reminderTime: string;
  deliveryMode: 'IMMEDIATE' | 'DAILY_DIGEST';
  digestTime: string;
  emailEnabled: boolean;
  version: number;
}
export interface Reminder {
  id: string;
  applicationId: string;
  kind: 'INTERVIEW' | 'DEADLINE' | 'FOLLOW_UP';
  company: string;
  role: string;
  label: string;
  eventDate: string;
  dueAt: string;
  state: 'PENDING' | 'READY';
  snoozed: boolean;
  version: number;
  emailStatus: 'NONE' | 'PENDING' | 'SENDING' | 'SENT' | 'CANCELLED' | 'FAILED';
}
export interface ReminderOverview {
  preferences: ReminderPreferences;
  emailAvailable: boolean;
  reminders: Reminder[];
  readyCount: number;
}
export function preferencePayload(p: ReminderPreferences) {
  return { enabled: p.enabled, interviewsEnabled: p.interviewsEnabled, deadlinesEnabled: p.deadlinesEnabled,
    followUpsEnabled: p.followUpsEnabled, interviewLeadDays: p.interviewLeadDays, deadlineLeadDays: p.deadlineLeadDays,
    followUpLeadDays: p.followUpLeadDays, timezone: p.timezone, reminderTime: p.reminderTime,
    deliveryMode: p.deliveryMode, digestTime: p.digestTime, emailEnabled: p.emailEnabled };
}
export const reminderApi = {
  overview: () => request<ReminderOverview>('/reminders'),
  savePreferences: (p: ReminderPreferences) => request<ReminderOverview>('/reminders/preferences', {
    method: 'PUT', ...jsonBody(preferencePayload(p)), headers: { 'Content-Type': 'application/json', 'If-Match': String(p.version) },
  }),
  snooze: (r: Pick<Reminder, 'id' | 'version'>, minutes: number) => request<Reminder>(`/reminders/${encodeURIComponent(r.id)}/snooze`, {
    method: 'POST', ...jsonBody({ minutes }), headers: { 'Content-Type': 'application/json', 'If-Match': String(r.version) },
  }),
  dismiss: (r: Pick<Reminder, 'id' | 'version'>) => request<Reminder>(`/reminders/${encodeURIComponent(r.id)}/dismiss`, {
    method: 'POST', headers: { 'If-Match': String(r.version) },
  }),
};
