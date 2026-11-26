# Add application button

The workspace brief calls for one primary action above the application list. The button redesign calls for a more serious look within the paper-and-ink palette. Solid ink uses 6px corners, a 48px minimum height, 13px semibold Manrope, and a divider before the trailing plus. The shape change applies only to this action. [Workspace design](workspace-design.md#direction-contract)

The whole button opens the new-application drawer. Its visible label supplies its accessible name, and it stays disabled while the workspace loads or another operation blocks changes.

## Compare the designs

Run `npm run dev` from the repository root and open `/docs/designs/add-application-buttons.html` at the printed URL. Switch appearance, tab through the controls, and select a treatment to see it above the list of choices. The samples give local feedback when activated. [Interactive comparison](designs/add-application-buttons.html)

- **Solid ink** pairs a filled button with a separated trailing plus. This is the design used in the workspace.
- **Outline** uses a paper background, an ink border, and a leading plus to give the action less visual weight.
- **Document** uses squarer corners and a document-plus icon to suggest adding a record.

## Review brief

> Review the main Add application button against the workspace design. Check its label, focus ring, disabled state, and fit on narrow screens in both themes. Open, cancel, and reopen a draft, and verify that these actions send no write. Compare the three designs while keeping the existing application flow.

## Verification

- `npm test` passed all 128 frontend tests. The drawer test opens a blank draft, types a company, closes the drawer, and checks that the next draft is empty. It also checks that these actions send no write request. [Workspace navigation tests](../tests/workspace-navigation.test.mjs)
- `npm run build` passed TypeScript checking and produced the Vite production bundle.
- Headless Chrome rendered the workspace and comparison at 375px and 1280px in light and dark mode. The workspace button measured 48px high at both widths, showed a 2px focus outline, and opened an empty drawer. All three comparison controls switched designs and showed preview feedback. The browser run used synthetic account data.
- With reduced motion enabled, Chrome showed the workspace fills as `#171817` in light mode and `#eeeee6` in dark mode. The calculated enabled text contrast for default, hover, and pressed states was 17.80, 11.59, and 10.15 to 1 in light mode and 15.27, 17.80, and 10.53 to 1 in dark mode.
- Independent source review found no blocking defects. Backend and HTTP/restart tests were outside the scope of this visual change.
