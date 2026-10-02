# Campus API

## Password recovery and change

All routes require the current CSRF header and same-origin session cookie; obtain a token from `GET /api/auth/csrf`. Disabled mail returns 503 `PASSWORD_MAIL_UNAVAILABLE`.

| POST route | Access | JSON fields | Success |
| --- | --- | --- | --- |
| `/api/auth/password-reset/request` | Public | `email` | 202 `PASSWORD_RESET_REQUEST_ACCEPTED` |
| `/api/auth/password-reset/confirm` | Public | `token`, `newPassword` | 200 `PASSWORD_RESET_COMPLETED` |
| `/api/auth/password-change/request` | Signed in | `currentPassword` | 202 `PASSWORD_CHANGE_REQUEST_ACCEPTED` |
| `/api/auth/password-change/confirm` | Initiating session/account | `token`, `newPassword` | 200 `PASSWORD_CHANGE_COMPLETED` |

Success bodies contain `status` and `message`. Unknown and recipient-suppressed recovery addresses receive the same 202 as existing addresses. Issuance does not change credentials. RESET expires after 30 minutes, CHANGE after 15; opening a link does not consume it. Tokens have 43 base64url characters, are single-use and purpose/account bound. The browser clears the URL fragment and holds the token in memory; reload requires reopening the email.

Confirmation enforces signup password policy and atomically replaces credentials, consumes/revokes challenges, increments credential version, and enqueues notification. Old sessions fail their next protected request. The current browser session ends too, including if another account was signed in during recovery; other accounts' passwords and other sessions are unaffected. Sign in normally afterward.

Errors include `code`: 400 `PASSWORD_CHALLENGE_INVALID` for expired/used/wrong-purpose/account/session tokens, 400 `PASSWORD_POLICY_FAILED`, 400 `CURRENT_PASSWORD_INVALID`, 429 `RATE_LIMITED` with Retry-After, and 503 `PASSWORD_SERVICE_UNAVAILABLE`. Invalid fields/unknown JSON are 400, bodies over 4096 bytes are 413, missing authentication is 401, and invalid CSRF is 403. Responses are not cacheable. Failed writes are never replayed; for a lost confirmation response, try sign-in or request fresh recovery.

This checkout has no signup verification state, so all registered accounts are recovery-eligible. This feature verifies possession of a fresh password email link without adding signup verification.

All routes are under `/api`. The production JAR serves React and the API from the same origin. During development, Vite proxies these routes to Java. Cross-origin access is not enabled.

## Authentication

1. `GET /api/auth/csrf` returns `{ "token": "…", "headerName": "X-CSRF-TOKEN" }` and establishes a session.
2. Include that header and the session cookie on every POST, PUT or DELETE.
3. `POST /api/auth/register` accepts JSON: `displayName`, `email`, `password` (12–72 characters with uppercase/lowercase/number/symbol, at most 72 UTF-8 bytes), and `includeSamples` (boolean). Registration does not sign in automatically; the React client signs in after success.
4. `POST /api/auth/login` accepts **form-urlencoded** `email` and `password`. It returns the signed-in user's `id`, `displayName` and `email`.
5. Fetch `/api/auth/csrf` again after login because Spring Security rotates the session and clears the pre-login CSRF token.
6. `GET /api/auth/me` returns the signed-in profile. `POST /api/auth/logout` invalidates the session.

The CSRF token is kept only in frontend memory. The session cookie is HttpOnly. API authentication errors return JSON rather than an HTML login redirect.

## Applications

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/applications?page=0&size=50&sort=updated&q=&status=` | List this account's active records; page size 1–100 |
| GET | `/applications/{id}` | One owned, non-deleted application, with ETag |
| POST | `/applications` | Create an application |
| PUT | `/applications/{id}` | Replace editable fields; requires `If-Match` |
| DELETE | `/applications/{id}` | Soft-delete; returns the new record/version for undo |
| POST | `/applications/{id}/restore` | Restore a deleted record; requires its current version |

`sort` accepts `updated`, `company`, `deadline`, or `applied`. `status` uses the labels below. The `q` filter is case-insensitive over company, role, location and notes. The frontend additionally searches interview-stage names in its cached history. Deleted records are excluded from listing and ordinary GET.

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
- **403**: missing/invalid CSRF token or registration is disabled.
- **404**: application is missing, belongs to someone else, or is not in the required deleted state.
- **409**: duplicate account, stale version or a database conflict.
- **428**: a version header is required.

`GET /api/health` is a public, minimal process-health response. It does not expose database contents or infrastructure details.
