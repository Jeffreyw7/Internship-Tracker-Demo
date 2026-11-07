# Campus workspace

Mode: Operate. Scope: the signed-in application list, interviews, follow-ups, application drawer, CSV import, navigation, and appearance controls.

The user explicitly extended the landing/account paper-and-ink identity to this workspace. This supersedes earlier design guidance retaining green workspace branding. Preserve account ownership, server-confirmed mutations, search/filter/sort, pagination, native dialogs, import recovery, undo, and light/dark/system preference behavior.

## Direction contract

THESIS: bring the same Campus identity into daily tracking, with data and actions carrying the hierarchy.

OWN-WORLD: self-hosted Manrope, warm paper and charcoal surfaces, yellow selection accents, black primary actions in light mode, cream primary actions in dark mode, soft 16px panels and pill controls. Status and error colors remain semantic.

STORY: find an opportunity, inspect or update its details, then take the next interview or follow-up action without changing navigation or persistence behavior.

FIRST VIEWPORT: compact Campus wordmark and persistent left navigation; account controls above a quiet paper welcome panel; one primary Add application action; a connected summary strip; readable search, filters, and application rows. Existing summary and radar shortcuts remain actionable. Phones use an overlay navigation and stacked records.

FORM: user-directed extension of the established Campus world. Keep the operational structure and familiar controls; no new visual-world selection applies. Motion acknowledges interaction only, with reduced-motion support.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Verification

Run frontend flow and palette tests plus the TypeScript/Vite build. Check desktop/mobile and both themes when browser access is available; document any inspection limitation. This change introduces no raster assets, backend behavior, external integrations, or automatic reminders.

The implemented workspace uses scoped semantic CSS and retains its existing data flow. Mobile navigation now focuses the selected view, confines keyboard navigation, and makes the background inert; close, Escape, selection, and desktop resizing release it. The skip link focuses the workspace without changing its route.

All 95 frontend tests and the production build passed. Independent source review found no remaining material regression after the mobile focus fix and test-fixture correction. Native-browser layout and interaction review remain blocked by local connection errors. See [validation results and limits](../VALIDATION.md#2026-10-31-workspace-alignment).
