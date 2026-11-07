# Campus authentication

Mode: Operate. Scope: sign-in, account creation, check-email, and verification continuation. These screens extend the established landing identity; the authenticated workspace keeps its semantic light/dark/system theme.

## Composition

The shared Campus header, paper background, ink typography, self-hosted Manrope, and black pill action connect account entry to the landing. A quiet story panel reuses the original folder staircase beside a focused form. The form heading, labelled fields, primary action, account switch, and verification help establish the task order. Yellow is a small heading marker and status accent.

Desktop uses two columns in the existing public container, with a form capped at 410px. At widths of 900px and below, the decorative story panel is hidden and the form centers at up to 430px. At 600px and below, the form cap is 410px, input text is 16px, and the footer stacks. The decorative artwork carries no required form instructions.

## Controls and continuation

- Preserve existing auth API calls, session lookup and expiration, registration validation, disabled/busy states, alert messages, hash navigation, and server-confirmed login/logout. Signup retains the optional 10 illustrative sample applications.
- Keep visible labels and autocomplete hints. The shared password field adds a 44px show/hide control with a changing accessible name, pressed state, and input association. Signup requirements remain attached to the password field.
- Keep visible keyboard focus. Destination title changes focus the account heading; Skip to form also focuses it. Home links and account switching clear the relevant credential/error state through the existing flow.
- Registration and verification-required login continue to Check your email. Resending uses the existing generic eligibility response. Confirmation requires the email-link token and signup password; opening the link alone does not verify an account. Invalid/expired links, resend, sign-in continuation, and switching accounts retain their existing paths. Verification preserves a currently signed-in account.
- The footer explicitly labels the selector **Workspace appearance**. It reuses `ThemeSelect` and the account-aware theme controller, including saving/error/retry behavior. Public account pages remain light while the selector controls workspace appearance.
- Reuse the public color tokens. The only new account colors are the input border and three error roles recorded in [DESIGN.md](../DESIGN.md) and `src/theme/tokens.css`. Preserve reduced-motion support.

## Handoff and verification limits

The implementation is in `src/auth.css`, `AuthLayout.tsx`, `PasswordField.tsx`, `AuthGate.tsx`, and `EmailVerification.tsx`. This change does not add backend behavior or deployment.

The existing Figma file was not updated because the Starter plan tool limit prevented further changes. Live visual inspection was blocked: the sandbox development server was unreachable from the in-app browser, and browser security blocked local-file navigation. No screenshot or visual-verification result is claimed. Source and build checks are recorded separately in [VALIDATION.md](../VALIDATION.md).
