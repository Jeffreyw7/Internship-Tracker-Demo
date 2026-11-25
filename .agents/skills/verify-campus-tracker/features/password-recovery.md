# Recover a password

Use the runner’s verified synthetic account with an existing application and an independent signed-in browser session. Its local SMTP listener captures the reset message in memory. The account password changes during this journey.

| Entry point or state | Drive and observe | Trial |
| --- | --- | --- |
| Signed-out sign-in screen → **Forgot password?** | Fill **Account email** and click **Request reset link**. Expect the generic inbox message. After acceptance the form also offers **Request another reset link**. | Passed for the first request. Repeat request not run |
| Captured reset link opened signed out | Open the link in Chrome. **Choose a new password.** appears and the fragment is cleared. Enter mismatched values and check the alert, then match them and submit **Reset password**. Reload the other signed-in session to see **Welcome back.**. The old password fails and the new one opens the existing record. | Passed |
| Reset link opened while signed in | On the reset screen choose **Sign out to reset a password** to continue, or **Return to current workspace** to leave. Expect the reset form only after signing out for this branch. | Not run |
| Return to an unfinished reset after refreshing or opening an invalid link | Reopen the captured mail link, or use **Request a new reset link** from the signed-out recovery screen. Expect the request form or a usable fresh form after opening valid mail. | Not run |

Inspect `Password reset email, mismatch, completion, prior session revocation and old/new password` in `results.json`. External mail delivery and hosted reset transport require their own evidence. [Request form](../../../../src/components/ForgotPassword.tsx) · [Reset form](../../../../src/components/ResetPassword.tsx)
