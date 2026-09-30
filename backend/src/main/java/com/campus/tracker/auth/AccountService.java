// Creates accounts transactionally so optional sample records and the account succeed or roll back together.
package com.campus.tracker.auth;

import com.campus.tracker.application.SampleDataService;
import com.campus.tracker.common.ApiException;
import com.campus.tracker.auth.verification.EmailVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
public class AccountService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SampleDataService samples;
    private final boolean registrationEnabled;
    private final EmailVerificationService verification;
    // Receive persistence, hashing, sample-data, and registration configuration dependencies.
    public AccountService(UserRepository users, PasswordEncoder encoder, SampleDataService samples,
                          EmailVerificationService verification,
                          @Value("${campus.registration-enabled}") boolean registrationEnabled) {
        this.users = users; this.encoder = encoder; this.samples = samples; this.verification = verification;
        this.registrationEnabled = registrationEnabled;
    }
    // Validate the password, normalize the email, and save only its BCrypt hash.
    @Transactional
    public AuthDtos.Accepted register(AuthDtos.Registration input) {
        if (!registrationEnabled) throw new ApiException(HttpStatus.FORBIDDEN, "Registration is disabled for this workspace.");
        PasswordPolicy.validate(input.password());
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            encoder.encode(input.password());
            return accepted();
        }
        CampusUser user = users.saveAndFlush(new CampusUser(email, input.displayName().trim(), encoder.encode(input.password())));
        // Seed examples only when the new user explicitly requested them.
        if (input.includeSamples()) samples.seed(user.getId());
        verification.issue(user);
        return accepted();
    }

    private AuthDtos.Accepted accepted() {
        return new AuthDtos.Accepted("VERIFICATION_REQUEST_ACCEPTED",
                "If this address can receive verification mail, a message will arrive shortly.");
    }
}
