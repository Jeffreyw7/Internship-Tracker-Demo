# CSV import architecture review

Date: 2026-10-15. Scope: system design and repository integration, not implementation or a repository-wide security scan.

Reviewed artifact: [CSV import architecture](../specs/2026-10-15-csv-import-design.md). References below point to the final revised design; original draft line numbers are intentionally not used after revisions.

## Review process and result

The parent inspected the supplied feature brief, current source, API/architecture documentation, migrations, deployment grants and test structure, then wrote the architecture. Three independent GPT-6.1 Sol reviewers at High reasoning ran in parallel with separate scopes: backend transactions/recovery, security/resource controls, and frontend semantics/accessibility. All three completed before consolidation. The parent revised the design, all three completed focused rechecks, and the security reviewer additionally rechecked the fixed-length transport restriction after identifying a chunked-upload timeout bypass.

Eight distinct findings were consolidated from overlapping reports: one P1 and seven P2. All eight are resolved in the written architecture; no concrete design blockers remain from these reviews. The receipt issue was rated P1 by the transaction reviewer and P2 by the other reviewers; this report retains P1 because the mandatory maximum batch could not complete under the original cap. Resolution means the specification addresses the failure, not that unimplemented behavior has been tested.

The parent cannot change its own model or reasoning preset through the available tools; the requested reviewer model/effort settings were explicitly applied to all three subagents.

## Capacity and data integrity

### R1 — P1: mandatory receipts exceeded their reserved capacity

The draft allowed 500 records and required row outcomes with UUIDs and versions, but capped each receipt at 32 KiB. Independent compact serializations of 500 Add outcomes exceeded 53,000 bytes, so a valid batch would either roll back or break quota accounting.

Resolution: 128 KiB receipt cap and matching admission reservation, bounded row/timestamp fields, and serializer byte checks. A conservative parent-side sizing check using 500 rows, 19-digit version strings and 32-character timestamps measured 63,174 bytes for Add, 64,674 for Update and 66,174 for Unchanged, below 131,072. String versions deliberately overestimate numeric serialization overhead; this is a design bound check, not a production serializer test.

References: `docs/superpowers/specs/2026-10-15-csv-import-design.md:144` (receipt), `:175` (capacity proof and reservation); integration points `ApplicationService.java` and import metadata persistence.

Required implementation evidence: maximum Add/Update/Unchanged batches, actual serialized byte bounds, atomic persistence, duplicate retrieval, restart and restricted PostgreSQL role.

## Transaction recovery and quota correctness

### R2 — P2: expired status could hide a still-finishing commit

Commit could pass its expiry check, continue past TTL, and complete while an unlocked GET saw the old PREVIEW and returned terminal 410. The UI would discard its recovery pointer before the successful receipt became visible.

Resolution: acquire and reread under the metadata commit lock before terminal expiry. Contention returns nonterminal 202 UNRESOLVED. Only a validated terminal outcome can clear the recovery pointer; remount restores the unresolved mutation lock before enabling writes.

References: design `:127`, `:140`, `:201` and `:203`; integration `src/hooks/useApplications.ts:23`, `src/data/api.ts:47`.

Required evidence: pause a commit after expiry admission, advance time, perform status lookup, then complete and recover the same receipt with no replacement import.

### R3 — P2: expiry could release a reservation before its commit finished

Wall-clock expiry alone could free a receipt reservation still needed by a transaction that had already been admitted. Another preview could consume that capacity before the first transaction stored its receipt.

Resolution: every persisted PREVIEW remains charged, including expired rows. Only a row-locked deletion or terminal transition releases its reservation; cleanup skips locked commits.

References: design `:148` (lock ordering), `:159` (retention), `:177` (admission accounting).

Required evidence: saturated owner/global quota, commit paused across TTL, concurrent admission and cleanup, no early reservation release.

### R4 — P2: separate aggregate queries could miss a completion transition

Quota locks do not block commits. At READ COMMITTED, reading completed totals and PREVIEW reservations in separate statements could miss an import in both categories if it completed between those reads, permitting over-admission.

Resolution: compute owner/global counts and bytes in one SQL aggregate snapshot after quota locking. Each row contributes its full PREVIEW reservation or its actual completed receipt; commit conversion can only reduce reserved bytes.

References: design `:177`; deployment/integration points `deploy/postgres/runtime-grants.sql:1`, `backend/src/test/java/com/campus/tracker/PostgresPrivilegesIT.java:15`.

Required evidence: concurrent PREVIEW-to-COMPLETED conversion at quota boundaries for counts and bytes on real PostgreSQL. PostgreSQL's [READ COMMITTED documentation](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED) explains why successive statements may use different snapshots.

## Frontend workflow and uncertain outcomes

### R5 — P2: normal corrections exhausted the five-preview quota

Every mapping/correction refresh originally retained another preview for 30 minutes. After five iterations an ordinary user could no longer obtain a committable preview.

Resolution: optional owner-bound predecessor supersession, atomic storage/reservation credit and successor creation, immutable review payloads, commit/supersession serialization, and bounded tombstones for lost successor response lookup. Failed successor creation preserves its predecessor. Completed or unresolved commits cannot be replaced.

References: design `:107`, `:109`, `:111`, `:159`, `:177`; integration `src/hooks/useApplications.ts:23`.

Required evidence: more than five correction cycles, independent tabs still obey quotas, failed replacement, lost successor response, supersession racing commit and tombstone expiry.

### R6 — P2: malformed success responses were missing from reconciliation rules

The draft named network/5xx/timeouts but omitted body-stream failures, malformed JSON and invalid receipt shapes after a successful server commit. Existing shared transport returns `response.json()` without receipt-shape validation, so these failures could appear as ordinary import failure.

Resolution: every post-dispatch outcome without an authoritative rejection or runtime-validated receipt is unknown. Keep the reference and mutation lock, reconcile via GET, and never automatically POST again. Invalid status bodies likewise do not establish a terminal result.

References: design `:201`, `:203`; existing transport `src/data/api.ts:71` and `:72`.

Required evidence: truncated committed 200, rejected body stream, wrong content type, invalid receipt/status shapes, session switch and recovery without automatic writes.

## Upload security and bounded work

### R7 — P2: CSRF parameter fallback could parse multipart before admission

The proposed upload filter followed CSRF, but current Spring Security configuration only selects a token repository. Without a header, parameter fallback could trigger multipart parsing/spooling before upload permits were acquired.

Resolution: pre-CSRF missing-header rejection for import POSTs with no parameter/body access, normal validation for present headers, authorization before admission, no eager MultipartFilter/getParts, and route exclusion from standard servlet multipart resolution. An admitted, bounded streaming reader owns multipart decoding.

References: design `:179`, `:181`; current configuration `backend/src/main/java/com/campus/tracker/config/SecurityConfig.java:60`.

Required evidence: real embedded-container requests, not just prebuilt MockMvc multipart requests; anonymous/missing/invalid-header requests must create no parts/temp files, and early failures retain no-store. Relevant upstream behavior: [Spring CSRF token handler](https://raw.githubusercontent.com/spring-projects/spring-security/6.5.x/web/src/main/java/org/springframework/security/web/csrf/CsrfTokenRequestAttributeHandler.java).

### R8 — P2: byte and parse limits did not bound slow uploads

A parse-only deadline did not cover body reception while scarce permits were held. The first revision's before/after servlet-read checks also could not interrupt a single read stuck in chunked-transfer framing while extension/trailer bytes arrived often enough to avoid an inactivity timeout.

Resolution: initially support fixed-length HTTP/1.1 at the application connector; reject absent length, transfer encodings and unsupported transports before admission. Use bounded streaming multipart decoding, an end-to-end deadline, bounded blocking reads, no body swallowing and permit/temp cleanup. HTTP/2-facing proxies need bounded buffering and an absolute upload deadline before forwarding fixed-length HTTP/1.1. Direct HTTP/2 upload support needs separate design verification.

References: design `:170`, `:181`, `:183`, `:185`; configuration integration `backend/src/main/resources/application.yml:41`. Upstream framing behavior is visible in [Tomcat ChunkedInputFilter](https://raw.githubusercontent.com/apache/tomcat/10.1.x/java/org/apache/coyote/http11/filters/ChunkedInputFilter.java).

Required evidence: actual browser FormData through Vite and bundled serving; raw HTTP stalled/dripping fixed-length bodies; chunk headers/extensions/trailers rejected before admission; capacity recovered within the documented 35-second bound. Proxy compatibility is a material implementation/deployment constraint.

## Covered areas and remaining verification limits

Reviewers found no additional design blocker in owner-scoped target resolution, explicit candidate selection, update counts, KEEP/SET/CLEAR, complete-record validation, ordered stage replacement, legacy-stage disclosure, optimistic parent versions, single-transaction application changes/receipt, policy incompatibility, account/revision guards, full-history refresh or separating import success from refresh failure. Accessibility requirements cover keyboard controls, focus/error links, review pagination, large-value expansion, progress announcements and theme contrast.

These remain implementation obligations. Current ordinary Bean Validation is controller-driven and business rules are private service code; shared validation extraction is required (`backend/src/main/java/com/campus/tracker/application/ApplicationController.java:42`, `ApplicationDtos.java:25`, `ApplicationService.java:115`). Existing Node tests do not establish React focus/lifecycle or screen-reader behavior. Real PostgreSQL permissions, container upload behavior, runtime serialization, concurrent races and browser compatibility must be proven by the verification matrix in design section 8.

Documentation verification performed: 21 existing integration paths confirmed present; Markdown fence balance, placeholders, trailing whitespace and local review/spec links checked; conservative receipt sizing executed with Node. An initial Python helper was unavailable through the installed launcher and the equivalent checks succeeded with Node. No application source was modified. Frontend/backend/build/HTTP/PostgreSQL suites were not run because this task produced design documents only. No design requirement is reported as an implemented or passing application feature.

Next handoff: user review of the written architecture, then an implementation plan if requested.
