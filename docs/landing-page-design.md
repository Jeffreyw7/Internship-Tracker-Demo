# Campus landing page

Mode: Persuade. Scope: the public entry surface and navigation into existing authentication. The subsequent [workspace refinement](workspace-design.md) extends this identity to signed-in tracking while preserving its behavior.

## Direction
User-supplied reference: monochrome editorial page with oversized sans-serif type, generous negative space, black pill actions, and a silver ribbon. The user's follow-up asks for greater distance from that reference. Replace the orbit with an original paper staircase: layered folders for Saved, Applied, and the next conversation. A pale yellow top folder marks the next action without implying an offer guarantee.

The latest user request extends the supplied account-page preview's paper panels, stronger type, black pill controls, and restrained yellow accents to the home page. Preserve the existing content, folder artwork, public routes, and the path into signup.

First viewport: compact Campus wordmark and navigation above a softly rounded gray paper panel. The left column groups the bold "Big ambitions. Clear next steps." heading, explanatory copy, registration action, preview link, and privacy note. The original diagonal staircase of application folders fills the right column. The hero uses normal grid flow rather than absolute copy positioning; below 800px it stacks copy and artwork. A thin baseline introduces applications, interviews, and follow-ups.

Following sections: an interactive, explicitly illustrative workspace preview; a three-step setup sequence on an inset paper surface; native expandable FAQs; a matching light closing invitation with a black registration action. No invented metrics or testimonials. Mobile retains the same reading order and lets actions wrap.

## Direction contract
THESIS: carry the account preview's calm paper-and-ink identity through the public entry page while keeping the next action clear.

OWN-WORLD: the existing warm off-white, gray paper, near-black ink, pale yellow, self-hosted Manrope, 16px paper corners, and black pill actions. Landing display and section headings use the available 800 face.

STORY: understand the tracker, explore its illustrative applications/interviews/follow-ups, then create an account, verify email, and sign in.

FIRST VIEWPORT: a compact header above a two-column paper panel, strong headline and grouped actions at left, original ascending folders at right. Narrow screens put the copy before the artwork without fixed content heights. Retain the finite folder entrance and pointer interaction, with reduced-motion support.

FORM: user-directed refinement of the established home page to match the account preview; no new visual-world selection or concept seed applies.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Core design principles
- Hierarchy: one dominant headline and a single primary action per decision point.
- Balance and space: asymmetrical copy/artwork hero, generous section spacing, denser product proof.
- Alignment and proximity: a consistent page gutter and grouped labels/actions.
- Contrast and repetition: near-black/off-white foundation, yellow reserved for the next-step folder and small decorative markers; black actions, shared typography, and consistent control dimensions.
- Accessibility: semantic landmarks, skip link, visible keyboard focus, 44px targets, native disclosure controls, labeled preview switching, reduced-motion support, and no dependence on color alone.
- Motion: The folders arrive once over 750ms with a 100ms stagger. On devices with a fine pointer, hovering the artwork gently separates the papers over 650ms; leaving returns them to rest. Calls to action respond in 180–240ms. Preview tab content changes over 280ms without moving focus; FAQ answers open over 220ms and the mobile menu over 240ms. Motion uses transforms and opacity with no loops or animation dependency. Reduced-motion preferences remove spatial animation and transitions; color, selection, and focus feedback remain immediate.

## Implementation and verification
Use React/TypeScript, plain CSS, existing Lucide icons, and self-hosted Manrope. Landing colors are scoped semantic tokens in src/theme/tokens.css. Hash navigation avoids introducing server routing requirements. Verification retains priority; signed-in sessions open the existing app; expiration opens sign-in with the existing message. No backend changes.

Verify with navigation regression tests, npm test, npm run build, and desktop/mobile browser inspection when available. The existing Figma landing composition predates this paper-panel refinement; no synchronization is implied by the local implementation.

Working assumptions: visitors are prospective student users and registration is the primary action; the existing deployment still decides whether registration is enabled. This is a local implementation and design handoff, not a deployment.
