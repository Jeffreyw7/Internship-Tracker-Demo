# Interview times and calendar export

Status: proposed design for review; implementation has not started.
Branch: `interview-time-and-calendar-export`.

## Outcome and scope

Users can record an interview's start and end, meeting link, and interviewer contact, then download that saved interview as a calendar event. The app automatically uses the current user's browser time zone for entry and display. Downloadable events are the first delivery; two-way calendar synchronization is a later feature.

This design extends the existing application drawer and interview schedule. It does not introduce a separate scheduling system. Existing stage dates, IDs, ordering, completion, undo, and application version checks remain usable.

Proposed scope decisions: one primary interviewer per stage (name, role/title, and email); one `.ics` download per saved, timed stage; optional times so an interview whose time is still unknown can retain its date. Multiple interviewer contacts and a bulk calendar download can be added separately if needed.

## Current project behavior

- `src/domain/application.ts` defines stages with `id`, `name`, `date`, and `state`. Scheduled stages require a date, and activity ordering compares dates only.
- `src/components/ApplicationDrawer.tsx` edits and displays those stages. `src/App.tsx` displays interviews, completes stages, supports undo, and exports application data as CSV.
- `src/data/applicationApi.ts` explicitly maps editable stage fields into API payloads. `src/data/api.ts` expects JSON and provides session/error handling.
- Java `InterviewStage`, `ApplicationDtos`, and `ApplicationService` persist a `LocalDate` and reconcile stable stage IDs within each owned application.
- Flyway currently has V1 and V2. Both H2 and PostgreSQL are supported. Writes use the parent application's optimistic version, and unknown JSON properties are rejected.

## Approach

| Option | Benefits | Tradeoff |
| --- | --- | --- |
| Java generates a saved stage's calendar file (recommended) | Exports the current persisted data; reuses ownership checks; centralizes formatting and time validation | Needs an authenticated download route and a frontend file-response helper |
| Browser generates calendar files | Reuses the already loaded application; no download endpoint | Can export stale data; splits calendar validation and serialization between clients |
| Provider integration now | Could support eventual updates in both directions | Adds account linking, provider credentials, conflict handling, and background synchronization beyond this first delivery |

Use the existing Spring Boot service and database. Add a small calendar formatter and shared frontend scheduling helpers. No calendar provider account is required for downloading a file.

## User flow

In the application drawer, each stage keeps its name, state, and date. Add visibly labeled **Start time**, **End time**, and an **End date** that defaults to the start date, so overnight interviews are explicit. Blank times mean the time is unknown; filling either time requires both times and the necessary dates.

Show **Time zone: America/New_York - detected from your device** using the actual detected zone. There is no routine time zone dropdown. Times use the user's locale and date-specific offset; the example zone is not a global default.

Add optional **Meeting link**, **Interviewer name**, **Interviewer role**, and **Interviewer email** below the schedule fields. A focused stage editor component keeps these fields out of the drawer's existing dense inline markup. All fields have visible labels, keyboard access, and stage-specific errors.

In the saved interview journey and interview list, display the local date, start/end range, and zone. Show both dates when the interview spans local calendar days. Date-only stages show **Time not set**. A saved meeting URL exposes **Join meeting** in a new tab, and contact details appear in the drawer.

Each saved stage with complete times exposes **Download calendar event**. A date-only stage instead shows **Add times to download a calendar event**, leading to editing that stage. Downloads use the saved record; unsaved edits must be saved first. Completed timed stages remain downloadable from their details, while completed stages remain excluded from the active interview list as today.

The download helper reports errors in the existing UI and releases its temporary object URL. The user imports the file into their calendar. The product explains: **Calendar downloads are snapshots. Changes here do not update your calendar automatically.**

## Automatic time zone behavior

Read the current zone from `Intl.DateTimeFormat().resolvedOptions().timeZone`. Resolve it on workspace load and when returning to the tab; refresh it before initializing an editor. When a detected-zone change occurs while an editor is open, preserve the draft and require reopening its schedule editor before changing times, rather than reinterpreting typed values.

Use the detected zone for all timed interview displays and edits. An interview originally saved as 2:00 PM New York time displays as 11:00 AM after the user switches to Los Angeles. Its instant remains unchanged. Reading a record, refreshing the list, or editing unrelated fields must never move an interview.

Persist absolute start/end instants plus the IANA zone used when the schedule was saved. The stored zone describes the original entry and its calendar date; it does not override the viewer's current zone. Editing the schedule uses the viewer's current zone and updates the stored zone. Keep the stored instants and zone when saving only notes, contacts, state, or other application fields, including completion and undo.

The schedule conversion helper must detect nonexistent daylight-saving local times by checking that converting to an instant and back reproduces the entered components. Reject a spring-forward gap with a field error. A repeated fall-back time defaults to its earlier occurrence for a newly entered time; show the offset beside the time. When editing an existing repeated-time interview, retain its original occurrence unless the user changes that endpoint. This policy avoids silently moving existing records.

The backend validates each submitted offset against the supplied IANA zone at that local date/time before converting to an instant. Reject unknown zones, gap times, and incorrect offsets. Use Java zone rules rather than a current fixed offset. If automatic detection is unavailable or the server does not recognize the zone, retain existing records and explain that timed scheduling needs a supported device zone; do not silently assume New York or UTC.

## Stage data and API contract

Extend the current stage shape with these fields:

| Field | Write representation | Meaning |
| --- | --- | --- |
| `startAt` | ISO 8601 offset date-time or null | Full start date/time, including the date-specific numeric offset |
| `endAt` | ISO 8601 offset date-time or null | Full end date/time, including its own offset |
| `timeZone` | IANA identifier or null; max 100 characters | Automatically detected zone used to enter the schedule |
| `meetingUrl` | String; max 2048 characters | Optional absolute HTTP/HTTPS meeting URL |
| `interviewerName` | String; max 120 characters | Optional primary contact name |
| `interviewerRole` | String; max 120 characters | Optional title or team |
| `interviewerEmail` | String; max 254 characters | Optional email address |

Keep the existing `date` field. For a timed stage it is the start's local calendar date in the submitted/stored `timeZone`. Reject a supplied non-null date that disagrees with that start date; derive the date if omitted. For a date-only stage it retains today's behavior. UI activity dates for timed stages are derived from the instant in the viewer's current zone, rather than this compatibility date.

Example stage write inside the existing application request:

```json
{
  "id": null,
  "name": "Technical interview",
  "state": "Scheduled",
  "date": "2026-10-08",
  "startAt": "2026-10-08T14:00:00-04:00",
  "endAt": "2026-10-08T15:00:00-04:00",
  "timeZone": "America/New_York",
  "meetingUrl": "https://example.com/interview",
  "interviewerName": "Alex Chen",
  "interviewerRole": "Engineering manager",
  "interviewerEmail": "alex@example.com"
}
```

Responses return offset date-time strings calculated from the stored instants in the stored zone, so response values can be reused in a write without losing offset information. Optional absent timestamp/zone/text response fields use empty strings, matching the existing frontend convention; absent request timestamps and zone use null. Older request JSON may omit new properties; handle that absence distinctly from explicit clearing as specified below.

Client payload mapping must whitelist these fields and exclude transient local editor values and server-owned metadata. Separate local date/time draft inputs from the persisted stage values. Validate a complete three-field schedule or no schedule; both endpoints are required together, and end must be strictly after start. Completed and planned stages may also retain a full schedule. Scheduled stages still require a date when their times are unknown. No guessed duration or midnight start is written.

Validate optional email syntax and trim contact fields. Meeting URLs must have an HTTP/HTTPS scheme, a host, no embedded credentials, and no control characters. Render contacts as text and meeting links with `noopener noreferrer`; calendar text and URI serialization receive their own validation. No server fetch of a meeting URL is needed.

Continue using POST/PUT application routes and `If-Match`. For an existing timed stage, omission of all three schedule properties by an older client preserves its schedule. Explicit nulls for all three clear it and retain the submitted date. Partial presence or a partial schedule is a 400 error. Missing new contact/link properties preserve existing values on that stage; explicit empty strings clear them. New stages default omitted values to empty. This presence-aware mapping avoids erasing new data when an older client replaces an application. Removed stages still follow existing orphan-removal behavior.

## Persistence and migration

Add `V3__interview_scheduling.sql` if V3 is still available at implementation time. Leave existing migrations unchanged.

Add nullable `start_at` and `end_at` as `TIMESTAMP(6) WITH TIME ZONE`, nullable `time_zone VARCHAR(100)`, and non-null contact/link columns with empty-string defaults and the limits above. Map timestamp columns as `Instant`; parse write timestamps as `OffsetDateTime` and convert only after zone validation.

Add a check that schedule columns are either all null or all non-null with end after start. Keep `stage_date`, the parent foreign key, and the ordering index. Existing rows receive empty contact fields and null scheduling fields. Do not infer times, duration, or a zone from an old date or the Java host's zone. Existing sample stages remain date-only until edited, since registration does not currently capture a browser zone.

Reconcile additions through `ApplicationService.apply` using each existing stage ID. Do not recreate a stage when its time or contact changes, because its ID also anchors the exported event UID. Parent application versions and update timestamps continue to cover these changes.

## Calendar download contract

Add authenticated `GET /api/applications/{applicationId}/stages/{stageId}/calendar.ics`. Resolve the non-deleted application through the existing owner check, then locate the stage within that application. Another owner's application, a deleted application, and a stage outside the selected application all return 404. A missing session returns 401. A stage without complete times returns 400 with **Add start and end times before downloading this interview.**

Return `text/calendar; charset=utf-8`, `Content-Disposition: attachment` with a safe filename such as `interview-{stageId}.ics`, and `Cache-Control: no-store`. Do not require `If-Match` for this read. Generate from the current saved record without changing its version.

The frontend needs a session-aware file request helper alongside JSON requests. It sends `Accept: text/calendar`, checks status and content type before creating a Blob, and shares current session-expiry and JSON-error behavior. A failed response never becomes a downloaded `.ics` file.

The formatter follows RFC 5545:

- One `VCALENDAR` with `VERSION:2.0`, a stable product identifier, and one `VEVENT`.
- Stable `UID` derived from the persisted stage UUID; `DTSTAMP` and `LAST-MODIFIED` use the application's saved update time.
- UTC `DTSTART` and exclusive `DTEND`, avoiding floating times and embedded zone definitions.
- `SUMMARY` includes company and stage; `DESCRIPTION` includes role, interviewer contact, original zone, and the meeting link. Exclude private application notes. Set the event `URL` to the meeting link when present and `CLASS:PRIVATE` as a calendar hint.
- UTF-8, CRLF lines, correct text escaping, and folding at 75 octets without splitting a code point.

Omit invitation methods, attendees, organizers, and alarms. Interviewer email is descriptive contact information. Stable UID helps identify the event but cannot guarantee how a provider handles repeated imports. Updates, completion, stage removal, or application deletion do not send calendar changes; this remains a download workflow.

## Frontend integration

Add focused scheduling helpers for zone detection, converting local editor values into strict offset date-times, retaining untouched endpoints, formatting timed ranges, and deriving viewer-local dates. Keep date-only helpers for application deadlines and follow-ups.

Extend activities with optional start/end instants. Sort by viewer-local date; on the same day put untimed items first, then timed interviews by their start instant, then use a stable ID tie-breaker. Display dates, today's labels, next-step text, and radar interview summaries using the same derived date/time. A timed interview is overdue once its end is past; the existing date-only overdue rule still applies to untimed items. Past scheduled interviews remain actionable until marked complete.

Update CSV stage summaries to include the time range, stored zone, meeting URL, and interviewer contact without dropping the existing stage name, state, and date. Do not add calendar controls to deadline or follow-up flows.

## Verification required for implementation

1. Java integration coverage: create/update/read timed stages, overnight ranges, invalid/partial schedules, incorrect zones/offsets, date mismatch, contacts/URL validation, stable stage IDs, optimistic conflicts, completion, and undo.
2. Compatibility coverage: V2-to-V3 migration preserves old dates and all account data; old requests preserve new stage fields; explicit null/empty clearing works; removing a stage still removes it. Verify both H2 and PostgreSQL migration behavior.
3. Time coverage: New York and Los Angeles views show different local dates/times for the same instant; DST gaps are rejected; repeated-time edits preserve the existing occurrence; fractional offsets work; changing device zone does not move saved events. Test tab return and draft handling.
4. Export coverage: unauthenticated and cross-account requests fail; deleted or wrong-parent stages fail; date-only exports return a useful error; download performs no write; a standards parser reads generated instants, IDs, and contacts correctly. Include Unicode folding, commas, semicolons, slashes, and embedded newlines to check serialization.
5. Frontend transport/domain coverage: field mapping preserves timing/contact details through complete/undo, file requests retain credentials and session-expiry handling, errors are not saved as calendar files, sorting and local-day transitions are correct, and object URLs are released.
6. Browser review: save, reload, edit, complete/undo, follow a meeting link, and download a real file with keyboard navigation and a narrow viewport. Import a sample into at least two available calendar clients and record results and any limits; do not claim provider behavior without checking it.
7. Run `npm test`, `npm run build`, and `backend/mvnw.cmd test`; adapt existing HTTP smoke coverage for the new download route. Record implementation results in `VALIDATION.md`.

These are planned acceptance checks. This design-only branch has not changed product behavior or run implementation tests.

## Later two-way synchronization

The saved stage ID, absolute instants, source zone, and parent version provide a usable foundation. A later design must address provider authorization, remote event mappings, inbound changes, conflict rules, deletions, token storage, and reconciliation. No provider credentials, OAuth endpoints, webhooks, or background calendar jobs are part of this delivery.

## Reference specifications

- [ECMA-402: DateTimeFormat resolved options](https://tc39.es/ecma402/#sec-intl.datetimeformat.prototype.resolvedoptions) defines the resolved time zone returned by the browser API.
- [Java 17 ZoneRules](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/zone/ZoneRules.html) documents valid offsets and gap/overlap handling.
- [RFC 5545](https://www.rfc-editor.org/rfc/rfc5545) defines iCalendar event properties, UTC date-times, escaping, and line formatting.
