# Scalability implementation record

Updated October 8, 2026. Branch: `scalability-design`. Repository implementation and local qualification are complete; final deployment recovery checks passed, as recorded in [VALIDATION.md](../VALIDATION.md). Hosted large-SaaS acceptance remains pending.

## Implemented architecture

The browser uses bounded cursor pages, whole-account SQL summaries, bounded activity feeds and on-demand details. Its account-scoped cache holds at most 20 pages and 100 details. Mutations stay serialized, owner checked, CSRF protected and versioned; stale responses cannot cross account changes. Completing an activity retains its displayed application version, so a newer schedule produces a conflict instead of being cleared. Calendar changes invalidate both pages and due summaries.

Same-origin static ingress routes to replicated API containers. Redis provides shared sessions and atomic authentication limits, with bounded cardinality, sanitized allowlisted session values and fail-closed outages. PostgreSQL remains the transactional primary. A one-shot migrator gates API and worker startup; separate runtime, migration, mail and export roles preserve privilege boundaries. Private probes, metrics, deadlines, drain settings and connection budgets support operation.

Dedicated mail and export workers claim short fenced leases. SMTP and storage operations run outside database transactions. Mail recovery can deliver a duplicate after provider acceptance, using the same token and Message-ID; confirmation remains single use. Export workers stream bounded pages to a private capped spool, publish private objects through durable intents, and fence READY completion. Downloads require current authentication and ownership. CSV traverses records created by the job cutoff; values remain live rather than a historical snapshot.

Native H2 startup embeds bounded export processing. Regular PostgreSQL Compose uses a restricted export worker and a shared private volume mounted read-only by the API. The scalable topology uses private shared object storage and separate workers. The unsupported regular-Compose Redis shortcut has been removed; distributed filesystem storage remains rejected.

## Verification and remaining acceptance

Fresh final checks: 62 frontend/load-runner tests, strict TypeScript/Vite build, 112 backend tests and 12 real PostgreSQL/Redis/private-storage/worker integration scenarios. Real child JVMs are killed after SMTP acceptance and private export upload. Default local startup, regular PostgreSQL Compose exports, real Chrome/full CSV and HTTP/restart persistence are checked. See [validation](../VALIDATION.md) for final replicated deployment results and evidence paths.

Two disposable load runs used eight accounts, 5,353 applications and 16,059 stages. The second embedded image identities and explicit CPU/memory limits in its report. It started 400/400 requests at 20/s, 1,000/1,000 on the 50/s ramp, 1,490/1,500 with mail/CSV, and 2,999/3,000 during a one-minute 50/s overlap. Foreground phases had no 5xx/transport failures or 429. Their maximum route p99 values were respectively 150.1, 51.7, 359.2 and 27.0 ms. Four exports and two mails completed. These measurements preceded the final review fixes and establish a modest local reference only.

At 200/s, the second run started 2,344/3,000 arrivals, missing 656 through scheduler lag with no capacity drops. This identifies a load-client limitation, not server saturation or 200/s server capacity. Incorrect-password 5/s admission produced 29 legitimate 429 responses among 50 requests; mixed-outcome p99 was 870.9 ms. Overlap recorded nine runtime, two export-worker and two mail-worker connections, within the configured 24-connection maximum; Redis used about 1.62 MB of its 64 MB budget.

Pending: selected provider/account, budget, origin, regions and approved SLO/RPO/RTO/retention; hosted workflow execution; matched original-version performance comparison; full population and 1,500/s sustained / 2,000/s burst; successful-auth peaks; multi-hour soak; managed failover/logout durability, rollback and isolated PITR. Prepare platform IaC after those inputs. No public deployment, paid provisioning or new publication is implied. The overnight automation remains paused.

## Rulings made

The following records every implementation/review ruling in order, including its practical limit.

| Decision | Reason | Cost or limit if wrong |
| --- | --- | --- |
| Use the user-selected feature branch and checkout | The user created and named it for this work | Outside edits require checking; shared-checkout isolation is weaker than a separate worktree |
| Adapt bookkeeping to PowerShell | Helper scripts require Bash | Equivalent ledger bookkeeping can contain transcription errors |
| Use cached Maven 3.9.11 | Windows wrapper previously failed | Results depend on equivalent profiles and Java compatibility |
| Retain ledger and uncommitted changes | Publishing authorization covered the earlier checkpoint | Current fixes are not yet backed up by a new commit/push |
| Scrape token authorizes only GET Prometheus | Account roles must not grant management access | Token distribution and private ingress remain operational requirements |
| Use explicit Maven repository and reviewed test access | Sandbox home/cache access failed | Native Java 20 compiling release 17 alone would not qualify Java 17; Docker Java 17 checks now supplement it |
| Use versioned allowlisted JSON sessions | Only sanitized identity, CSRF and scalar metadata are needed | New attribute types require explicit compatibility tests |
| Continue code before Redis runtime became available | Distributed checks were blocked locally and stayed visibly unverified | Qualification could reveal further defects; the runtime is now available and tests pass |
| Add maintenance indexes in V4 | Applied V3 lacked bounded-maintenance access paths | Two extra indexes add write/storage overhead |
| Reuse measured V1 owner/update index | Real PostgreSQL used a backward scan; a duplicate index added no demonstrated benefit | Other distributions/sorts may need measured indexes later |
| Use installed Chrome and Playwright | The alternate browser CLI was unavailable | CLI-specific behavior is untested; actual browser flows are exercised |
| Use SeaweedFS 4.48 for private S3 fixtures | The proposed public MinIO image was unavailable | Hosted object-store compatibility must be qualified separately |
| Retain that fixture for portable deployment | Private SDK behavior was already qualified | This single-host storage fixture does not establish managed storage resilience |
| Allow eight storage volumes with one-volume growth | Metadata and export collections competed for a single slot | Local storage resource sizing still needs measurement |
| Call the changed-stack phase a reference | No equivalent original v2/export topology exists | Comparative performance benefit remains unmeasured |
| Report Windows scheduler drops explicitly | The client missed offered arrivals without concurrency saturation | True server saturation requires suitable load infrastructure |
| Keep hosted provisioning/failover/restore pending | Required hosting and operational inputs are unavailable | Production availability/recovery targets remain unqualified |
| Keep full-scale load and multi-hour soak pending | Modest local runs do not establish the proposed targets | Production saturation remains unknown |
| Claim no before/after speedup | No matched original-stack experiment exists | Optimization benefit has not been quantified |
| Keep password reset/account lifecycle separate | Those workflows are outside this refactor | Public-launch lifecycle requirements need separate work |
| Gate caching/search/brokers/replicas/sharding on evidence | Current measurements justify none of them | A qualified production workload may require additional architecture |

## Review disposition

One fresh-context whole-branch reviewer found no Critical issues and four Important issues: stale activity completion, disabled default export processing, midnight summary rollover and the unsupported Compose shortcut. All four entered one fix pass with regression evidence and full-suite checks. No minor findings were deferred. The reviewer set aside the hosted, capacity, comparative-performance, lifecycle and optional-infrastructure items above; their rulings are explicitly recorded.
