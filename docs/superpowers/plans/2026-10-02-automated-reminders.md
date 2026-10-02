# Automated Reminders Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. The user has requested design and implementation in this session.

**Goal:** Deliver configurable, durable interview, deadline, and follow-up reminders with snooze and digest options.

**Architecture:** JDBC preferences, occurrences and leased email jobs in the existing Spring Boot database; a scheduled coordinator reconciles source data and releases due reminders. React consumes authenticated versioned APIs and shows settings and reminders.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Flyway, H2/PostgreSQL, React 19, TypeScript, existing SMTP support.

**Spec:** `docs/superpowers/specs/2026-10-02-automated-reminders-design.md`

## Global constraints

- Default disabled, email optional; exclude sample applications from reminders.
- Lead times 0–30 days; snooze 15 minutes to seven days.
- Existing date-only interviews use local reminder time, default 09:00 UTC.
- Owner-scoped, session/CSRF-protected writes with `If-Match` versions.
- No added product dependencies; no real mail during tests.

## Review focus

- A date moved or a follow-up completed must invalidate queued mail.
- Concurrent requests must not duplicate preferences, occurrences or deliveries.
- DST, timezone edits and late digest arrivals must not lose reminders.
- Snooze and dismiss must survive repeated reconciliation and restarts.
- Mail failure must leave usable in-app reminders and a visible delivery status.

## Task 1: Durable reminder lifecycle and API

Files: create `backend/.../reminder/ReminderDtos.java`, `ReminderService.java`, `ReminderController.java`, `ReminderProperties.java`, `ReminderTiming.java`, `db/migration/V3__automated_reminders.sql`; modify `application/ApplicationService.java`, `resources/application.yml`; create `ReminderIntegrationTest.java`, `ReminderTimingTest.java`.

Interfaces: `ReminderService.reconcile(UUID owner)` joins the caller's application transaction; `overview(UUID)`, `savePreferences(UUID,String,Preferences)`, `snooze(UUID,UUID,String,int)`, `dismiss(UUID,UUID,String)` expose owner-scoped operations. `ReminderTiming.due(LocalDate,int,String,String)` calculates UTC instants.

- [x] Write failing API tests: unavailable endpoint, isolated accounts, versions, range/timezone validation, three eligible source types, completed/deleted sources, date edits, sample exclusion, and persistent snooze/dismiss.
- [x] Run `.\mvnw.cmd -Dtest=ReminderIntegrationTest,ReminderTimingTest test`; verify expected missing-feature failures.
- [x] Implement migration, preferences, lifecycle, timing and API; wire application writes after flush.
- [x] Run targeted Java tests; expect zero failures.

## Task 2: Scheduler and durable SMTP jobs

Files: create `ReminderWorker.java`, `ReminderEmailSender.java`, `SmtpReminderEmailSender.java`, `ReminderDeliveryTest.java`; extend `ReminderService.java` with `processOwner(UUID)` and bounded owner iteration.

Interfaces: `ReminderEmailSender.send(String recipient,String subject,String body)` is called outside transactions; `ReminderWorker.runOnce()` reconciles, releases, batches, claims and delivers pending work.

- [x] Write failing tests for digest release/next-day arrivals, duplicate ticks, timezone boundaries, transient/permanent failures, stale jobs, expired lease recovery and disabled mail.
- [x] Run targeted delivery tests and verify missing-feature failures.
- [x] Implement transactional release/job assignment, conditional leases, six-attempt retry and stale-item filtering.
- [x] Run all Java tests; expect zero failures.

## Task 3: React reminder center and operational documentation

Files: create `src/data/reminderApi.ts`, `src/components/ReminderCenter.tsx`, `tests/reminders.test.mjs`; modify `src/App.tsx`, `src/styles.css`, `README.md`, `docs/API.md`, `VALIDATION.md`.

Interfaces: `reminderApi.overview/savePreferences/snooze/dismiss` use the shared request transport and versions. The center receives application-open and close callbacks; polling never replaces dirty preference fields.

- [x] Write failing client tests for complete preference payloads, version/CSRF headers, snooze/dismiss and conflict propagation.
- [x] Run the client test and verify missing-module failure.
- [x] Implement typed API, accessible responsive modal, settings, loading/error feedback, polling and application link.
- [x] Run `npm test`, `npm run build`, full Java tests, and temporary-backend browser/smoke verification where available.
- [x] Review the complete diff; record final verification and configuration limits in documentation. Leave all implementation on the requested branch; do not merge or deploy.

## Execution record

Task 1: API tests first failed with missing endpoints, then passed after lifecycle implementation and timezone/unknown-owner error fixes.

Task 2: Delivery tests first failed with missing worker/sender types. Review regressions reproduced four delivery failures, then passed after digest cutoff/empty-window, retry preservation, expiry and multi-batch corrections. SQL acknowledgement now shares the owner lock with preference/source actions. A Java 17 concurrency-test helper incompatibility was corrected.

Task 3: Client tests first failed with a missing adapter; final 21 client tests and production build passed. A real HTTP/restart smoke and desktop/mobile browser workflow passed. All 60 Java tests passed in bundled verification. Independent review findings were addressed; final scope remains the requested branch.
