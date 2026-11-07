# Application save recovery and history traversal

The user authorized implementation of the prior review's duplicate-create,
conflict-recovery, and pagination findings using multiple subagents. This plan
records implementation decisions; the existing uncommitted UI/design work is
preserved. No branch integration, deployment, or publication is part of the task.

## Behavior and interfaces

- Ordinary creates use an owner-scoped UUID `Idempotency-Key`. The server stores
  its result atomically with the application. An explicit retry with the same
  normalized payload returns the same application; a changed payload is rejected.
- `GET /api/application-creates/{key}` reports the current result. `DELETE` cancels
  an unresolved create atomically, retaining a tombstone so a delayed request
  cannot create an application after cancellation. Cancellation racing a committed
  save returns that application's result instead. The legacy no-key POST remains
  compatible; the workspace always supplies a key.
- The browser retains only owner/key/payload fingerprint in tab session storage,
  never application contents. Ambiguous failures retain the key and block other
  mutations. Reads may reconcile automatically; writes only follow user actions.
- The drawer keeps the base record and draft after a version conflict, fetches the
  latest record, and offers explicit discard or reapply choices. A three-way field
  comparison retains unrelated remote changes; overlapping fields require an
  explicit choice. Missing records and failed reads remain recoverable states.
- `GET /api/applications/history?size=100&cursor=...` uses immutable creation-time
  and ID keysets with a fixed initial upper bound. It returns content and a next
  cursor. This preserves full-history loading without offset shifts. It is a
  bounded traversal, not a multi-request database snapshot.

## Work allocation and acceptance

1. Backend create agent: durable keyed creates, outcome/cancellation endpoints,
   migration V5, PostgreSQL privileges, and concurrent/rollback/isolation tests.
2. Conflict agent: drawer recovery UI, merge helper, conflict/deletion/read-failure
   regressions, and pending-create controls using callbacks supplied by the parent.
3. Pagination agent: dedicated history endpoint/service, repository queries,
   migration V6 index, frontend history adapter, and changing-history tests.
4. Parent: create transport/pointer workflow and hook integration, App callbacks,
   targeted regression tests, API documentation, integrated verification/review.

Each implementation starts with a failing regression. Shared files have limited
ownership: the pagination agent owns only applicationApi's list import/body; the
parent owns other adapter/hook changes. The parent alone edits existing dirty App
and documentation files through narrow patches.

## Verification

Run frontend tests and TypeScript/production build; backend tests against isolated
H2; PostgreSQL privilege/integration tests if the existing runtime is available;
build the bundled JAR after the frontend build and run the maintained Node HTTP,
SMTP, and restart smoke. Review the combined patch for duplicate writes, stale
accounts, cancellation races, deleted records, cursor boundary behavior, and
preservation of existing edits. Report actual checks and limitations.
