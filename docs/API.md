# Campus API

All routes are under `/api`. The production JAR serves React and the API from the same origin. During development, Vite proxies these routes to Java. Cross-origin access is not enabled.

## Authentication

1. `GET /api/auth/csrf` returns `{ "token": "…", "headerName": "X-CSRF-TOKEN" }` and establishes a session.
2. Include that header and the session cookie on every POST, PUT or DELETE.
3. `POST /api/auth/register` accepts JSON: `displayName`, `email`, `password` (at least 12 characters with uppercase, lowercase, number and symbol; at most 72 UTF-8 bytes), and `includeSamples` (boolean). It returns HTTP 202 with `{"status":"VERIFICATION_REQUEST_ACCEPTED","message":"…"}`. Registration never signs in automatically.
4. Open the verification link from the email, then `POST /api/auth/email-verification/confirm` with JSON `token` and the signup `password`. A valid, unexpired token returns `{"status":"EMAIL_VERIFIED"}`. The user signs in normally afterward.
5. `POST /api/auth/email-verification/resend` accepts `{"email":"…"}` and returns the same HTTP 202 response for unknown, verified, exempt, pending, or recipient-throttled addresses. Global or IP throttling can return 429.
6. `POST /api/auth/login` accepts **form-urlencoded** `email` and `password`. It returns the signed-in user's `id`, `displayName` and `email`. Correct credentials for a pending new account return HTTP 403 with code `EMAIL_VERIFICATION_REQUIRED`; invalid credentials remain a generic 401.
7. Fetch `/api/auth/csrf` again after login because Spring Security rotates the session and clears the pre-login CSRF token.
8. `GET /api/auth/me` returns the signed-in profile. `POST /api/auth/logout` invalidates the session.

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
