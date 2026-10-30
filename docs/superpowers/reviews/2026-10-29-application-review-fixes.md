# Application issue review and fixes - 2026-10-29

Reviewed the existing Campus Internship Tracker checkout at main `94b062c`. It initially had no tracked changes; the unrelated `salvage-crew-gui-design-prompt.txt` was preserved. One GPT-6.1 Sol High reviewer was reused for the initial review and both follow-up passes. The reviewer was read-only and did not post externally.

## Findings and disposition

| Finding | Resolution | Files |
| --- | --- | --- |
| Another tab could load a newly signed-in account's records beneath the previous identity | Requests carry the established account ID; the server rejects a mismatched authenticated account before body processing, writes or logout. Storage signals trigger identity revalidation and invalidate old work. Late bodyless responses are also checked. | `src/data/api.ts`, `src/components/AuthGate.tsx`, `backend/src/main/java/com/campus/tracker/auth/AccountBoundaryFilter.java`, `backend/src/main/java/com/campus/tracker/config/SecurityConfig.java` |
| Mutable updated-time pagination could silently omit an application edited between pages | Full-history traversal uses immutable creation time and ID ordering. Changed totals, duplicates, or missing records produce a refresh error rather than a successful partial load. | `src/data/applicationApi.ts`, `backend/src/main/java/com/campus/tracker/application/ApplicationService.java` |
| Automatic account switching could discard an unresolved CSV import's recovery reference | Recovery references and clearing are scoped by owner. Legacy references migrate only for their owner, and failed migration preserves the original. A returning account regains its pending-import lock and read-only reconciliation. | `src/domain/applicationImport.ts`, `src/hooks/useApplications.ts` |
| npm audit reported two high-severity vulnerable packages | Updated Vite from 8.0.13 to 8.3.5 and source-map-js from 1.2.1 to 1.2.2 through the lockfile. The React plugin's peer range and Node runtime were checked. Clean installation and the final audit passed. | `package.json`, `package-lock.json` |

The reviewer confirmed the original two findings were resolved, found the import-recovery follow-on issue, and then reported **no actionable findings** after that issue was corrected. This is a bounded review of the inspected code, not a guarantee that the repository has no other defects.

Regression tests demonstrated failures before the corresponding fixes: incomplete 101-row history, stale account requests, late/overlapping profile responses, and loss of pending references during A-to-B-to-A switching. The React hook regression also covers independent pending imports for both accounts and confirms reconciliation does not replay either commit.

## Final verification

| Check | Result |
| --- | --- |
| Node 22.23.3 `npm ci --include=dev` | Passed |
| `npm test` | 79 passed; zero failures or skips |
| `npm run build` | Passed TypeScript checking and Vite production build |
| `npm audit --audit-level=low` | Zero reported vulnerabilities |
| Maven 3.9.11 clean verify with `with-ui,postgres-it` | 140 backend tests and 3 PostgreSQL integration tests passed; no skips |
| `.github/scripts/check_ci.py reports backend` | Confirmed all required reports/scenarios passed |
| CI guard unit tests | 16 passed |
| Final UI bundling after the recovery correction | Passed; all 5 dist files matched JAR entries byte for byte |
| `npm run test:smoke` | Passed real HTTP/assets, authentication/email verification, preferences, CRUD/undo, native multipart through bundled/Vite routes, CSRF, atomic conflict rollback, duplicate receipts, lost-response GET reconciliation, and application/import JVM restart persistence |
| `git diff --check` | Passed |

The final bundle was repackaged with tests skipped only after the clean backend verification had passed; the final frontend tests/build ran after the last frontend change. The backend sources did not change after their clean verification.

## Limits

- Local backend checks used installed Java 21.0.12.1 and disposable PostgreSQL 18.6 databases. The previously cached Java 17 installation was incomplete and could not run. Flyway warned that PostgreSQL 18 is newer than its tested support range.
- Docker was unavailable. Java 17/PostgreSQL 17 Testcontainers and container-image builds still require hosted CI on this patch; older green runs are not evidence for these uncommitted changes.
- Cross-tab React behavior was exercised in JSDOM, alongside real Spring Security integration tests. Native-browser rendering/focus behavior was not exercised in this pass.
- History traversal detects changed counts/boundaries but does not promise a transaction snapshot across HTTP requests.
- Changes remain uncommitted and unpushed.

Dependency references: [Vite 8.3.5 release](https://github.com/vitejs/vite/releases/tag/v8.3.5) and [source-map-js advisory](https://github.com/advisories/GHSA-68fv-2mgg-jv7q). The npm audit response supplied the installed-version vulnerability and remediation evidence.
