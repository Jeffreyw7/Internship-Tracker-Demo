/** Java also accepts offset and legacy zone IDs that some browsers cannot format. */
export function formatReminderTime(instant: string, timezone: string, locale?: string) {
  const options: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' };
  const date = new Date(instant);
  try {
    return new Intl.DateTimeFormat(locale, { ...options, timeZone: timezone, timeZoneName: 'short' }).format(date);
  } catch {
    const offset = /^(?:UTC|GMT|UT)?([+-])(\d{2})(?::?(\d{2}))?$/.exec(timezone);
    if (offset) {
      const minutes = (Number(offset[2]) * 60 + Number(offset[3] ?? 0)) * (offset[1] === '+' ? 1 : -1);
      const shifted = new Date(date.getTime() + minutes * 60000);
      return new Intl.DateTimeFormat(locale, { ...options, timeZone: 'UTC' }).format(shifted) + ' ' + timezone;
    }
    return new Intl.DateTimeFormat(locale, { ...options, timeZone: 'UTC', timeZoneName: 'short' }).format(date)
      + ` (${timezone} is unavailable in this browser)`;
  }
}
