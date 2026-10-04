# Campus API

All routes are under `/api`. The production JAR serves React and the API from the same origin. During development, Vite proxies these routes to Java. Cross-origin access is not enabled.

## Authentication

1. `GET /api/auth/csrf` returns `{ "token": "…", "headerName": "X-CSRF-TOKEN" }` and establishes a session.
2. Include that header and the session cookie on every POST, PUT or DELETE.
3. `POST /api/auth/register` accepts JSON: `displayName`, `email`, `password` (10–72 characters, at most 72 UTF-8 bytes), and `includeSamples` (boolean). Registration does not sign in automatically; the React client signs in after success.
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

## Authentication throttling

Login, registration, and CSRF/session-creating requests are rate-limited. Limits are process-local and reset on restart. A throttled request returns **429**, a positive integer `Retry-After` header (seconds), and the usual `{"message":"..."}` body. Clients must not automatically replay writes.

The CSRF budget also applies to anonymous unsafe requests on unrelated paths, because Spring may create a session before returning 403. Login rejects missing, duplicate, or oversized email parameters with 400 before password verification. Existing-account passwords are not subjected to registration complexity rules.

See [authentication security](AUTHENTICATION-SECURITY.md) for defaults, proxy configuration, and limits.
