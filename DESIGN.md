---
name: Campus
description: A shared paper-and-ink identity across public pages and the theme-aware Campus workspace.
colors:
  landing-surface: "#fafaf9"
  landing-panel: "#ffffff"
  landing-panel-alt: "#f0f0ed"
  landing-ink: "#171817"
  landing-on-ink: "#ffffff"
  landing-secondary: "#5e605e"
  landing-muted: "#6d706b"
  landing-line: "#dddfd9"
  landing-accent: "#e9ef8b"
  landing-accent-pale: "#f8facb"
  landing-action-hover: "#363a34"
  landing-on-dark-muted: "#babfb5"
  landing-lilac: "#eeebf8"
  landing-purple: "#61517b"
  landing-peach: "#fae9dc"
  landing-brown: "#885836"
  landing-blue-pale: "#e9eff8"
  landing-blue: "#416183"
  account-field-border: "#92968d"
  account-error-bg: "#fff2ee"
  account-error-text: "#8b3024"
  account-error-border: "#d69b90"
  workspace-action-light: "#171817"
  workspace-action-dark: "#eeeee6"
  workspace-surface-light: "#ffffff"
  workspace-surface-dark: "#22231f"
  workspace-text-light: "#171817"
  workspace-text-dark: "#f3f3eb"
  workspace-paper-light: "#f0f0ed"
  workspace-paper-dark: "#2b2c26"
  workspace-accent-light: "#e9ef8b"
  workspace-accent-dark: "#c6ca87"
typography:
  display:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "clamp(42px, 4.6vw, 68px)"
    fontWeight: 800
    lineHeight: 1.1
    letterSpacing: "-.04em"
  headline:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "44px"
    fontWeight: 800
    lineHeight: 1.18
    letterSpacing: "-.035em"
  title:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "18px"
    fontWeight: 800
    lineHeight: 1.5
    letterSpacing: "-.3px"
  body:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.85
  label:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: 1.65
  brand:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "31px"
    fontWeight: 800
    lineHeight: 1
    letterSpacing: "-1.2px"
  workspace-heading:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "36px"
    fontWeight: 800
    lineHeight: 1.18
    letterSpacing: "-.035em"
  workspace-body:
    fontFamily: "'Campus Manrope', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.6
rounded:
  company-mark: "6px"
  workspace-control: "7px"
  workspace-field: "8px"
  folder: "14px"
  paper: "16px"
  preview: "16px"
  status: "20px"
  action: "100px"
spacing:
  detail: "8px"
  compact: "12px"
  control-inline: "24px"
  mobile-gutter: "20px"
  tablet-gutter: "36px"
  desktop-gutter: "56px"
  section-intro: "65px"
components:
  landing-button-primary:
    backgroundColor: "{colors.landing-ink}"
    textColor: "{colors.landing-on-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.action}"
    padding: "14px 24px"
  landing-button-primary-hover:
    backgroundColor: "{colors.landing-action-hover}"
    textColor: "{colors.landing-on-ink}"
  landing-button-accent:
    backgroundColor: "{colors.landing-accent}"
    textColor: "{colors.landing-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.action}"
    padding: "14px 24px"
  landing-button-accent-hover:
    backgroundColor: "{colors.landing-accent-pale}"
    textColor: "{colors.landing-ink}"
  landing-text-link:
    textColor: "{colors.landing-ink}"
    typography: "{typography.label}"
  landing-preview:
    backgroundColor: "{colors.landing-panel}"
    textColor: "{colors.landing-ink}"
    rounded: "{rounded.preview}"
  landing-paper-panel:
    backgroundColor: "{colors.landing-panel-alt}"
    textColor: "{colors.landing-ink}"
    rounded: "{rounded.paper}"
    padding: "56px"
  landing-status:
    backgroundColor: "{colors.landing-lilac}"
    textColor: "{colors.landing-purple}"
    rounded: "{rounded.status}"
    padding: "4px 9px"
  landing-navigation:
    backgroundColor: "{colors.landing-surface}"
    textColor: "{colors.landing-ink}"
  landing-disclosure:
    textColor: "{colors.landing-ink}"
    typography: "{typography.label}"
    padding: "20px 0"
  landing-folder:
    textColor: "{colors.landing-ink}"
    rounded: "{rounded.folder}"
    padding: "24px"
    width: "306px"
  account-button-primary:
    backgroundColor: "{colors.landing-ink}"
    textColor: "{colors.landing-on-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.action}"
    padding: "14px 23px"
  account-field:
    backgroundColor: "{colors.landing-panel}"
    textColor: "{colors.landing-ink}"
    rounded: "{rounded.workspace-control}"
    padding: "12px 14px"
    height: "50px"
  workspace-button-light:
    backgroundColor: "{colors.workspace-action-light}"
    textColor: "{colors.landing-on-ink}"
    rounded: "{rounded.action}"
    padding: "10px 18px"
    height: "44px"
  workspace-field-light:
    backgroundColor: "{colors.workspace-surface-light}"
    textColor: "{colors.workspace-text-light}"
    rounded: "{rounded.workspace-field}"
    padding: "12px"
    height: "48px"
  workspace-button-dark:
    backgroundColor: "{colors.workspace-action-dark}"
    textColor: "{colors.workspace-text-light}"
    rounded: "{rounded.action}"
    padding: "10px 18px"
    height: "44px"
  workspace-paper-panel:
    backgroundColor: "{colors.workspace-paper-light}"
    textColor: "{colors.workspace-text-light}"
    rounded: "{rounded.paper}"
    padding: "32px"
---

# Design System: Campus

## Overview

**Creative North Star: "Small steps, new possibilities"**

The public landing identity gives a student's next action room to stand out. Bold, left-aligned Manrope headings and softly rounded gray paper panels surround a rising stack of application folders with a warm yellow next-step folder. Its character is spacious, direct, and optimistic; the sculptural artwork supports the language of progress.

The paper, ink, Manrope, and restrained yellow identity now extends through sign-in, registration, email verification, and the signed-in workspace. Public pages remain light regardless of the workspace preference. The workspace uses warm paper and charcoal in light mode and warm charcoal surfaces with cream actions in dark mode, preserving account-backed light/dark/system appearance. Its denser controls and operational structure serve daily tracking. Surface-specific compositions belong to the [landing](docs/landing-page-design.md), [authentication](docs/authentication-design.md), and [workspace](docs/workspace-design.md) briefs.

The token snapshot comes from [semantic colors](src/theme/tokens.css), [landing styles](src/landing.css), [landing components](src/components/LandingPage.tsx), [account styles](src/auth.css), [account layout](src/components/AuthLayout.tsx), [base workspace styles](src/styles.css), and the [workspace refinement](src/workspace.css). Update the code and documentation together when intentionally changing a reusable value. The existing [Campus Figma handoff](https://www.figma.com/design/7RYwF1BR4IiplQKBaq27zA) predates these home, authentication, and workspace refinements; those updates have not been added to it.

**Key Characteristics:**

- Spacious black-on-light typography with a compact Campus wordmark.
- An original paper staircase of saved roles, sent applications, and a yellow next-step folder.
- Black pill actions, quiet dividing lines, and softly curved paper and preview surfaces.
- An explicitly illustrative product preview with native, keyboard-accessible controls.
- One Campus identity with separate public and theme-aware workspace color roles.

## Colors

The public palette is mostly near-black, warm off-white, and neutral grey; yellow carries the visual emphasis while small pastel accents distinguish illustrative records.

### Primary

**Campus Ink** is the default heading, action, and icon color. **On Ink** provides the corresponding light action text. **Action Hover** is the established hover treatment for the black action.

### Secondary

**Next-Step Yellow** and **Pale Yellow** identify the next-step folder, text selection, and small decorative paper marks. Lilac/purple, pale blue/blue, and peach/brown are small illustrative company/status accents. They are not replacements for the workspace's semantic status palette.

### Neutral

**Page Paper** separates the public page from the white preview panel. **Quiet Panel** marks the hero, inset setup and closing panels, and preview footer. **Secondary Text** carries explanatory copy; **Muted Text** carries supporting emphasis and inactive preview tabs. **Hairline** divides navigation-adjacent content, tab rows, and disclosures. **On-Dark Muted** remains a legacy token; the current closing invitation uses light paper and ordinary ink/secondary text.

Account pages reuse these public colors. **Account Field Border** gives inputs a stronger boundary than Hairline. The three account error roles provide a warm error surface, readable message, and border; verification status uses the existing Pale Yellow.

The frontmatter's workspace entries summarize the paired semantic palettes in the token source. Light mode uses paper surfaces, charcoal actions, and pale-yellow selections. Dark mode uses warm charcoal surfaces, cream actions, and muted-yellow accents. Status, success, warning, and error colors remain distinct; matching the brand does not erase their meaning.

**The Surface Boundary Rule.** Share the landing color tokens across public landing and account surfaces. Keep signed-in workspace components bound to their existing semantic tokens and account-aware theme controller.

## Typography

**Display and body font:** self-hosted Campus Manrope, with a sans-serif fallback. Public pages use real regular, medium, semibold, and extra-bold faces; text hierarchy comes from size, spacing, and weight rather than a second display family.

The frontmatter describes desktop role baselines. Display and headline describe public headings; title is the product-preview explanation; body is introductory explanatory copy; label describes primary controls; brand captures the compact public wordmark. The workspace also uses Campus Manrope, with a fixed 36px welcome heading, 22px panel headings, 14px base text, and 12-13px operational controls. Its welcome heading steps down to 30px at 1200px and 28px at 520px; drawers use a 30px heading that becomes 26px on phones.

Headlines use tight tracking and balanced wrapping. Body copy is quieter, with generous line height and deliberately short measures. FAQ answers cap their measure at 65 characters. The largest hierarchy change is between the dominant headline and supporting copy; labels remain subordinate.

Responsive heading overrides are intentional: the main headline becomes 40px at 900px, uses `clamp(40px, 7vw, 56px)` when the hero stacks at 800px, and uses `clamp(30px, 8vw, 46px)` with a 1.12 line height at 600px. Section headings step down to 38px at 1150px and 32px at 600px. The closing heading uses 48px on desktop, inherits the 38px intermediate override, becomes 36px at 900px, and uses `clamp(27px, 7vw, 36px)` on phones. The FAQ heading starts at 40px before the shared responsive overrides. Landing display, section, preview-explanation, and setup titles use the extra-bold face; selected preview tabs also use that emphasis.

Account headings retain medium weight (500) with `clamp(32px, 3.1vw, 42px)`, tight tracking, and a 1.15 line height. Account story titles also retain medium weight (500). Labels remain compact, inputs use 15px text, and phone inputs increase to 16px.

**The Local Type Rule.** Use self-hosted Campus Manrope throughout, with compact fixed typography in the workspace and larger display typography on public pages.

## Layout

The shared public container centers at a maximum width of 1280px, with 56px side gutters at its default size. Gutters reduce to 36px at 1150px and 20px at 600px. The header, section boundaries, and footer align to this container. The desktop hero groups its heading, copy, actions, and privacy note beside the artwork in a normal-flow grid with 1.1fr/1fr columns. Hero, setup, and closing content use inset gray paper panels. Panel padding starts at 56px and reduces with the responsive rules.

The product preview uses a 280px explanatory column beside a flexible preview panel, separated by 70px. That layout becomes a single column at 900px. Setup content uses three equal columns before stacking at 800px. FAQ content moves from a two-column question/answer arrangement to a vertical flow at the same breakpoint.

At 900px the header uses a menu control in place of the desktop links. At 800px the hero becomes one column, with the grouped copy before the artwork and no minimum panel height. Hero actions wrap when needed. At 600px preview tabs become a horizontal row and each preview record wraps its next step beneath company and status. Reading order and actual control labels remain intact.

Account pages reuse the public container and gutters. A desktop two-column composition pairs a quiet folder-story panel with a form capped at 410px. At widths of 900px and below, the decorative story is hidden and the form centers at up to 430px; at 600px and below, its cap is 410px and the footer stacks. Form fields and their continuation actions remain in the reading flow.

The workspace keeps a 240px sidebar, reducing to 216px at 1200px. Main content uses 40px desktop side padding, then 28px, 24px, and 16px at its smaller breakpoints. Four connected summary shortcuts become two columns at 1000px. Navigation becomes an overlay at 800px; application rows retain their stacked mobile format at 700px. At 520px, tabs can scroll horizontally and forms use 16px input text. Tables scroll horizontally on intermediate widths rather than discarding fields.

Breakpoints and their effects live in the sidecar. Preserve the difference between the generous public page and the dense operational workspace; a landing-page gutter is not a replacement workspace spacing rule.

## Elevation & Depth

Most public surfaces are flat and separated by background changes or quiet lines. Depth belongs primarily to the application-folder illustration, the preview panel, and the opened mobile menu. Folded tabs, paper layers, restrained silver gradients, and diffuse shadows give the original staircase a tactile character.

The sidecar records the diffuse preview and menu shadows, plus the SVG folder shadow. The folders arrive once over 750ms with a 100ms stagger. On devices with a fine pointer, hovering the artwork gently separates the papers over 650ms; leaving returns them to rest. Calls to action respond in 180–240ms. Preview tab content changes over 280ms without moving focus; FAQ answers open over 220ms and the mobile menu over 240ms. Motion uses transforms and opacity with no loops or animation dependency. Reduced-motion preferences remove spatial animation and transitions; color, selection, and focus feedback remain immediate.

Account forms stay flat. Their primary action uses 180ms color/press feedback and a 220ms arrow movement. The account subtree also disables animation and transitions for reduced motion.

**The Material Depth Rule.** Use the implemented paper layers, gradients, and soft shadows to describe the folders; keep ordinary page structure flat and legible.

## Shapes

Actions use a full pill silhouette. Hero, setup, closing, and preview panels share soft 16px corners with the account story panel; status labels and small company marks use their own compact shapes rather than sharing one universal radius. Thin separators establish groups without enclosing every content block.

The signature illustration is editable SVG folder geometry with paper, silver, and pale yellow gradients. Each folder rotates 13 degrees counterclockwise; the stack rises from Saved to Applied to the next conversation. Keep this treatment attached to the illustration rather than functional forms.

The workspace uses 16px main panels, 12px supporting surfaces, 8px field corners, pill actions, and readable status chips. Its native drawer and import dialogs retain their existing behavior. Summaries remain clickable filter shortcuts in a connected strip.

## Components

### Buttons

Primary landing actions pair Campus Ink with On Ink, a pill shape, semibold text, and an inline arrow. The normal action has a 52px minimum height; compact header and mobile variants are defined in the stylesheet. The closing invitation uses the same black primary action on its light paper panel. Hover changes color; keyboard focus uses a visible two-pixel ink outline with an offset. The accent-button tokens describe an optional legacy variant still present in CSS; no current home-page action uses it.

Text actions use a directional SVG icon, a generous inline gap, and an underline on hover. Their wording describes the destination. Account submit actions reuse the black pill with a trailing arrow, a 52px minimum height, and visible ink focus. Workspace actions use theme-aware charcoal/cream fills, 44px minimum targets, and the corresponding semantic focus ring. Add application uses a 48px minimum height. Disabled, busy, error, and retry states remain visible.

### Chips

Preview status chips are quiet, compact labels with readable text. The active preview mode is identified by text weight and an arrow on desktop, or an underline on mobile; color is not its only distinction. These are illustrative presentation styles, not new backend status meanings.

### Cards / Containers

The white preview panel has rounded corners, a soft diffuse shadow, a divided header, tabular numerals, and an explicitly visible illustrative label. Its records are compact and responsive. The visible add symbol inside this preview is decorative, not a functional create button. Hero, setup, and closing panels use Quiet Panel with ink text and no structural shadow.

### Inputs / Fields

The public landing contains no data-entry fields. Account fields use white surfaces, Campus Ink, the account border role, and gently rounded 7px corners. They are 50px high with an ink focus outline. Password fields include a 44px show/hide control with an accessible state and name. Requirements stay associated with the signup password input; errors retain alert semantics and busy controls remain disabled.

Application editing uses the revised semantic palette, an 8px corner, and a 48px minimum field height. Labels, required states, errors, native date/select controls, and draft handling remain intact. The sidecar distinguishes fixed-light account fields from theme-aware workspace fields.

### Navigation

The Campus wordmark is a home link. Public navigation uses compact text links with clear keyboard focus; the mobile menu is an expanded/collapsed control, closes on selection, and supports Escape. A skip link becomes visible on focus. Public destination changes use the existing hash-navigation model.

Account pages offer Back to home and Skip to form. The account heading receives focus when the destination title changes; the skip link targets that heading. The footer explicitly labels the existing theme selector **Workspace appearance** and retains its account-aware controller.

Workspace navigation preserves Applications, Interviews, Follow-ups, pipeline filters, and the account profile. Skip to workspace focuses the main region without changing the URL. Opening mobile navigation focuses the selected view, confines Tab navigation, and makes background controls inert. Escape, close, or selecting a destination releases the menu; resizing to desktop releases it and focuses the workspace. Closed mobile navigation is hidden from keyboard access.

### Disclosures and Preview Switching

FAQ rows use native details/summary behavior with a trailing inline SVG state icon. The preview uses labelled tabs, a corresponding panel, and Left/Right/Home/End keyboard navigation. Preserve those states when changing the visual presentation.

### Next-Step Paper Staircase

Keep the artwork decorative and the explanation in ordinary page text. Three original vector folders ascend diagonally: Saved, Applied, and Your next conversation. The yellow folder sits above the others so its message remains unobscured. Paper layers, a 13-degree rotation, diffuse shadows, and one finite entrance sequence define the material treatment. It does not represent a guaranteed hiring outcome.

The home-page hero uses semibold folder titles (600) and hides the separate artwork caption; the shared SVG geometry and the account story's medium folder titles (500) remain unchanged.

## Do's and Don'ts

### Do:

- **Do** preserve the public/workspace token boundary and existing account appearance behavior.
- **Do** use the self-hosted Manrope faces and established public type hierarchy.
- **Do** keep the paper staircase editable and decorative, with its meaning available in ordinary text.
- **Do** preserve visible focus, native disclosure behavior, preview keyboard navigation, and reduced-motion support.
- **Do** label sample content as illustrative and keep functional claims aligned with the product.

### Don't:

- **Don't** hard-code public light colors into workspace components; preserve semantic status colors and account-backed light/dark/system behavior.
- **Don't** promote this landing's page composition or Persuade mode into a product-wide layout rule.
- **Don't** turn decorative preview symbols into controls without implementing the corresponding behavior.
- **Don't** turn the folder illustration into a generic card grid or obscure its labels with overlaps.
