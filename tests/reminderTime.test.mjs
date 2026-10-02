import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatReminderTime } from '../src/domain/reminderTime.ts';
test('Java fixed offset timezone IDs render with the configured offset',()=>{
  assert.match(formatReminderTime('2026-10-02T09:00:00Z','UTC+03:00','en-US'),/12:00/);
  assert.match(formatReminderTime('2026-10-02T09:00:00Z','GMT+05:30','en-US'),/2:30/);
});
test('browser-unsupported Java zones display a labelled UTC fallback',()=>{
  const original=Intl.DateTimeFormat;
  // ICU zone support differs by browser. Simulate a browser without Java's SystemV aliases.
  Intl.DateTimeFormat=function(locale,options){
    if(options.timeZone==='SystemV/EST5EDT')throw new RangeError('Unsupported timezone');
    return new original(locale,options);
  };
  try { assert.match(formatReminderTime('2026-10-02T09:00:00Z','SystemV/EST5EDT','en-US'),/UTC/); }
  finally { Intl.DateTimeFormat=original; }
});
