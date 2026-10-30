# Campus API

All routes are under `/api`. The production JAR serves React and the API from the same origin. During development, Vite proxies these routes to Java. Cross-origin access is not enabled.

## Authentication

1. `GET /api/auth/csrf` returns `{ "token": "…", "headerName": "X-CSRF-TOKEN" }` and establishes a session.
2. Include that header and the session cookie on every POST, PUT or DELETE.
3. `POST /api/auth/register` accepts JSON: `displayName`, `email`, `password` (at least 15 Unicode characters, without composition rules, checked against a password blocklist; at most 72 UTF-8 bytes), and `includeSamples` (boolean). It returns HTTP 202 with `{"status":"VERIFICATION_REQUEST_ACCEPTED","message":"…"}`. Registration never signs in automatically.
4. Open the verification link from the email, then `POST /api/auth/email-verification/confirm` with JSON `token` and the signup `password`. A valid, unexpired token returns `{"status":"EMAIL_VERIFIED"}`. The user signs in normally afterward.
5. `POST /api/auth/email-verification/resend` accepts `{"email":"…"}` and returns the same HTTP 202 response for unknown, verified, exempt, pending, or recipient-throttled addresses. Global or IP throttling can return 429.
6. `POST /api/auth/login` accepts **form-urlencoded** `email` and `password`. It returns the signed-in user's `id`, `displayName` and `email`. Correct credentials for a pending new account return HTTP 403 with code `EMAIL_VERIFICATION_REQUIRED`; invalid credentials remain a generic 401.
7. Fetch `/api/auth/csrf` again after login because Spring Security rotates the session and clears the pre-login CSRF token.
8. `GET /api/auth/me` returns the signed-in profile. `POST /api/auth/logout` invalidates the session.

The browser also sends `X-Campus-Account` with the profile ID established by login or `/auth/me`. When that optional header differs from the authenticated session, the server returns `401 ACCOUNT_CHANGED` before parsing the body or executing a write, including logout. It never selects or authorizes an account from this header. Cross-tab login/logout signals clear the old workspace and recheck `/auth/me`; the server check remains effective when browser storage notifications are unavailable.

The CSRF token is kept only in frontend memory. The session cookie is HttpOnly. API authentication errors return JSON rather than an HTML login redirect.

Verification links use 32 random bytes encoded as a 43-character base64url token and expire after 24 hours. Tokens are single-use. The browser removes the fragment from the address bar immediately and requires an explicit password-confirmation submission; opening or previewing the link does not activate an account. Accounts present when Flyway V3 is applied are exempt and continue to sign in without verification. Accounts created afterward are never exempt.

## Appearance preference

| Method | Path | Response |
| --- | --- | --- |
| GET | `/me/preferences` | `{ "themePreference": "system" }` |
| PUT | `/me/preferences` | Accepts and returns `{ "themePreference": "dark" }` |

`themePreference` must be exactly `system`, `light`, or `dark`. It belongs to the signed-in account; callers cannot choose a user ID. New and migrated accounts default to `system`. The PUT requires the current CSRF token. Invalid values return 400, missing authentication returns 401, and missing CSRF returns 403. Both routes bypass HTTP caching.

## Applications

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/applications?page=0&size=50&sort=updated&q=&status=` | List this account's active records; page size 1–100 |
| GET | `/applications/{id}` | One owned, non-deleted application, with ETag |
| POST | `/applications` | Create an application |
| PUT | `/applications/{id}` | Replace editable fields; requires `If-Match` |
| DELETE | `/applications/{id}` | Soft-delete; returns the new record/version for undo |
| POST | `/applications/{id}/restore` | Restore a deleted record; requires its current version |

`sort` accepts `updated`, `company`, `deadline`, `applied`, or `created` (immutable creation time ascending, then ID). The frontend uses `created` for full-history loading so edits cannot move records between pages. If totals change or the traversal contains duplicate/missing records, loading fails with a refresh prompt instead of accepting an incomplete history. This is not a transaction snapshot across requests. `status` uses the labels below. The `q` filter is case-insensitive over company, role, location and notes. The frontend additionally searches interview-stage names in its cached history. Deleted records are excluded from listing and ordinary GET.

List response:

```json
{"content": [], "totalElements": 0, "totalPages": 0, "page": 0, "size": 50}
```

Create/update request:

```json
{
  "company": "Example Labs",
  "role": "Software Engineer Intern",
  "location": "New York, NY",
  "status": "Saved",
  "appliedDate": null,
  "deadline": "2026-10-15",
  "followUpDate": null,
  "notes": "Prepare Java API examples.",
  "stages": [
    {"id": null, "name": "Recruiter screen", "date": null, "state": "Planned"}
  ]
}
```

Statuses: `Saved`, `Applied`, `Interviewing`, `Offer`, `Rejected`, `Withdrawn`. Stage states: `Planned`, `Scheduled`, `Completed`.

Use `null` for absent request dates. Response dates use empty strings when absent to match the date inputs. Application dates cannot be in the future and are required for statuses other than Saved. Scheduled stages require a date. At most 30 stages are accepted. Responses additionally contain `id`, `createdAt`, `updatedAt`, `version`, `sample` and `deleted`; never include those server-owned fields in create/update JSON.

For new stages, pass null or a client-generated UUID. The server assigns the persisted UUID. For updates, retain the stage IDs returned by the server. Supplied IDs can only reuse a stage already belonging to the same application; they cannot reference another user's stage. Array order determines stage order.

## Concurrency and undo

Updates, deletes and restores require `If-Match: "0"` (or the current numeric version). The version increments after every update. Missing `If-Match` returns **428**; a stale version returns **409**. The frontend does not automatically retry writes.

A successful DELETE response includes the incremented version. Use that version to restore the same ID. Undoing an ordinary edit instead submits the previous field values with the version returned by the edit. Both paths reject intervening changes instead of silently overwriting them.

## Errors

Errors use `{ "message": "Human-readable explanation" }`:

- **400**: malformed JSON, invalid fields, dates or sort values.
- **401**: no active login session or invalid login credentials.
- **403**: missing/invalid CSRF token, registration is disabled, or correct credentials belong to an account awaiting verification (`EMAIL_VERIFICATION_REQUIRED`).
- **404**: application is missing, belongs to someone else, or is not in the required deleted state.
- **409**: a stale application version or another database conflict. Duplicate registration uses the generic accepted response and never overwrites the existing account.
- **429**: an authentication or verification request exceeded an IP or global limit.
- **428**: a version header is required.

`GET /api/health` is a public, minimal process-health response. It does not expose database contents or infrastructure details.

## Authentication throttling

Login, registration, and CSRF/session-creating requests are rate-limited. Limits are process-local and reset on restart. A throttled request returns **429**, a positive integer `Retry-After` header (seconds), and the usual `{"message":"..."}` body. Clients must not automatically replay writes.

The CSRF budget also applies to anonymous unsafe requests on unrelated paths, because Spring may create a session before returning 403. Login rejects missing, duplicate, or oversized email parameters with 400 before password verification. Existing-account passwords are not subjected to registration complexity rules.

See [authentication security](AUTHENTICATION-SECURITY.md) for defaults, proxy configuration, and limits.

## Password reset

Both POST endpoints require the current session CSRF token and uncompressed JSON (maximum 4096 bytes), reject unknown/duplicate fields, and return no-store responses. Opening a link changes no server state.

- `/api/auth/password-reset/request`: `{ "email": "student@example.test" }` returns HTTP202 with status `PASSWORD_RESET_REQUEST_ACCEPTED`, including for unknown accounts and recipient suppression. It does not disclose queue or account identifiers.
- `/api/auth/password-reset/confirm`: `{ "token": "43-character-base64url-token", "newPassword": "a unique long passphrase" }` returns HTTP200 with status `PASSWORD_RESET_COMPLETE`. Canonical, unexpired tokens are single-use. Invalid/expired/used tokens return HTTP400 with code `PASSWORD_RESET_INVALID`.
- Capacity/abuse budgets return HTTP429 with Retry-After; disabled/unavailable recovery returns HTTP503. No write is automatically replayed. Oversize bodies return413, unsupported encoding/media415 and body timeout408.

Email links use `#reset-password=...`; the browser scrubs the fragment immediately and keeps the challenge in memory only. Sign out explicitly if already authenticated before submitting the UI form. Completion revokes prior account sessions and sibling challenges while preserving applications, preferences and verification status. Pending accounts still require verification, using their new password. Sign in normally afterward. See [operations](PASSWORD-RESET.md).

## CSV imports

Imports use the same authenticated session and `X-CSRF-TOKEN` header as ordinary application changes. Every import response bypasses HTTP caches. Uploaded values are rendered as text and never evaluated as formulas.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/application-imports/preview` | Parse a CSV and persist an immutable owner-bound review |
| POST | `/application-imports/{id}/commit` | Atomically apply the reviewed batch and save its completion receipt |
| GET | `/application-imports/{id}` | Read the review, successor reference, original receipt, or unresolved status |

Preview accepts `multipart/form-data` with one `file` part and an optional JSON `options` part. Source column indices are strings, starting at zero; row numbers start at one after the header. Example options:

```json
{
  "schemaVersion": 1,
  "mapping": {"0": "applicationId", "1": "company", "2": "role", "3": "notes"},
  "dateFormat": "ISO",
  "rows": [
    {"rowNumber": 2, "action": "Skip"},
    {"rowNumber": 3, "clears": ["notes"], "stageMode": "KEEP"}
  ],
  "supersedesPreviewId": null
}
```

Mapping destinations are `applicationId`, `company`, `role`, `location`, `status`, `appliedDate`, `deadline`, `followUpDate`, `notes`, `stages`, `legacyStages`, and `ignore`. Every column must be mapped or explicitly ignored before commit. A file-only preview returns the columns and validation issues for mapping; it changes no applications.

Row corrections can specify `action` (`Add`, `Update`, `Skip`), `targetId`, `values` (editable field names to literal strings), `clears`, `stageMode` (`KEEP`, `REPLACE`), and `acknowledgeLegacy`. Blank/unmapped update cells preserve existing values. Clearing optional dates produces null; clearing optional text produces an empty string. Required fields cannot be cleared. The full normalized record must satisfy ordinary application validation.

Application ID is a lookup control, not an editable ID. Unavailable, foreign or deleted IDs are rejected without falling back to Add. Normalized company/role matches only suggest candidates; Update requires an ID or explicit target selection. Multiple included rows cannot target one application, including rows that would be Unchanged.

Dates use `YYYY-MM-DD` by default. Alternative file policies are `MDY` (`MM/DD/YYYY`) and `DMY` (`DD/MM/YYYY`), with strict parsing and no locale guessing. `Interview stages JSON` maps to `stages` and accepts an array of `{ "name": "Screen", "date": null, "state": "Planned" }` objects without IDs. Existing stages are preserved unless REPLACE is chosen. An explicit replacement `[]` clears stages. Legacy flattened stages cannot be reconstructed reliably; acknowledge excluding them or provide structured replacements. Imported owner IDs, versions, timestamps, sample/deleted flags and stage IDs are forbidden.

Preview returns HTTP 201 and `{id,schemaVersion,policyVersion,createdAt,expiresAt,reviewDigest,committable,headers,rows,counts,issues}`. Rows include actions, field intents, server-captured target versions, normalized before/after values, differences, candidates and issues. A row-error review is persisted but cannot commit until corrected or explicitly excluded. Any edit needs a new preview. Pass the previous `supersedesPreviewId` when revising: successor creation and predecessor retirement share one transaction and quota reservation. GET of a retired predecessor identifies its successor if the response was lost.

Commit accepts only the review digest and an explicit update acknowledgement:

```json
{
  "reviewDigest": "the digest returned by preview",
  "acknowledgement": {"existingApplicationsToUpdate": 2}
}
```

The exact count is required even for zero updates. Commit rechecks ownership, deletion state, target versions, complete validation, expiry and policy compatibility. All included application changes and the minimal receipt commit together, or none do. Skip and Unchanged rows do not write applications. Repeated commits return the original receipt without repeating changes. A new preview ID is a new import and can create duplicates; candidate matching is advisory.

GET returns `PREVIEW`, `COMPLETED` with its receipt, or `SUPERSEDED` with a successor ID. A locked status check that cannot establish terminal expiry returns HTTP 202 `UNRESOLVED` with Retry-After. An unlocked PREVIEW response does not prove a previously dispatched commit failed. After a lost/truncated/malformed commit response, reconcile using GET; never automatically replay POST or create a replacement import. Explicit retry uses the same reviewed ID. The frontend keeps only an owner-bound recovery reference in per-tab sessionStorage and blocks further application writes while the outcome remains unknown.

Previews expire after 30 minutes. Their payload is erased on successful commit; minimal receipts remain for 30 days. Expiry cannot release a reservation while an admitted commit still holds the metadata row. Terminal 410 requires synchronized expiry determination; foreign/missing/purged IDs return 404 and never initiate work. Cleanup deletes metadata in bounded batches.

Default ceilings are 2 MiB file, 500 data records, 32 columns, 64 KiB scalar cells, 256-byte headers, 256 KiB options, 3 MiB multipart request, 8 MiB canonical review and 128 KiB receipt. Limits include ignored columns and excluded rows. Admission also enforces persisted owner/global quotas and rates. Imports initially require fixed-length HTTP/1.1 at the application connector; chunked bodies, compressed bodies, absent length and direct HTTP/2 are rejected before body admission. A proxy must enforce bounded bytes and an absolute upload deadline, then forward fixed-length HTTP/1.1. Browser-generated FormData must supply its own Content-Type/boundary; do not set that header manually.

The upload uses bounded in-memory parts, with no temporary files. Previews and confirmations share one active write per owner and four per process, acquired after authentication/CSRF and before body reads; status GETs remain available while a write holds capacity. Confirmation JSON is limited to 4096 bytes. The absolute preview/upload and confirmation-body deadline is 30 seconds; a blocked socket read has a 5-second timeout, bounding body-read permit recovery to 35 seconds even when bytes arrive continuously. A connector-owned timeout may return an empty HTTP 408 rather than the application JSON error envelope. Parsing also has a 10-second CPU/work deadline. `campus.imports` configuration can lower the input/time/concurrency defaults listed in `application.yml`; the existing `preview-work-ms` and `process-preview-count` settings also govern confirmation reads and shared write capacity. Startup rejects increases above protocol ceilings. Permits remain held through commit completion; commit transactions retain their separate 30-second bound, import JDBC statements 5 seconds and application target-lock queries 2 seconds.

Persisted admission defaults are 5/100 active previews and 40/800 MiB preview payload bytes per owner/globally; 2,000/100,000 retained receipts and 64 MiB/2 GiB receipt bytes including reservations; and 300/10,000 uncommitted metadata rows including superseded tombstones. Every PREVIEW reserves 128 KiB for a receipt, including expired previews until a safe locked transition. These storage quotas are configurable through the corresponding `campus.imports.owner-*` / `global-*` properties. Lower limits can reject new work while existing metadata drains through normal expiry; they do not erase existing records.

Persisted one-minute rate windows default to 10/60 previews, 10/60 commits and 60/600 status requests per owner/globally (`owner-preview-rate`, `global-preview-rate`, and the corresponding `commit`/`status` properties under `campus.imports`). Cleanup runs every 60 seconds by default (`cleanup-delay-ms`) and deletes at most 100 eligible imports per pass, skipping busy rows. Receipt and preview retention are fixed policy values, not browser-clock decisions.

Import errors include 400 malformed/forbidden content, 401 authentication, 403 CSRF, 404 unavailable metadata, 409 changed targets/policy/review, 410 expired/superseded preview, 411 required length, 413 resource limit, 422 invalid/unacknowledged review, 429 rate/storage/work capacity, and 503 busy database work. Treat ambiguous failures after commit dispatch as unknown outcomes and check status. Successful commit plus a failed history refresh is still a successful import; retry only the read.

Exports now include Application ID and structured stages without stage IDs. Exports and downloaded reports quote CSV and prefix formula-leading text to reduce spreadsheet risk. Spreadsheet behavior varies, and a reimport can preserve that literal safety apostrophe; lossless round trips are not promised.


Pending CSV import recovery references in `sessionStorage` are scoped to the owner account. Switching accounts never overwrites or clears another account's unresolved import. Returning to that account restores its write lock and read-only status reconciliation. Existing single-slot references migrate only when their owner returns, and remain readable if storage quota prevents migration. References contain only the owner and preview IDs, never uploaded rows or credentials.
