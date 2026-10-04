// Creates accounts transactionally so optional sample records and the account succeed or roll back together.
package com.campus.tracker.auth;

import com.campus.tracker.application.SampleDataService;
import com.campus.tracker.common.ApiException;
import com.campus.tracker.auth.verification.EmailVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.util.Locale;

@Service
public class AccountService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SampleDataService samples;
    private final boolean registrationEnabled;
    private final EmailVerificationService verification;
    private final TransactionTemplate registration;
    // Receive persistence, hashing, sample-data, and registration configuration dependencies.
    public AccountService(UserRepository users, PasswordEncoder encoder, SampleDataService samples,
                           EmailVerificationService verification,
                           PlatformTransactionManager transactions,
                           @Value("${campus.registration-enabled}") boolean registrationEnabled) {
        this.users = users; this.encoder = encoder; this.samples = samples; this.verification = verification;
        this.registrationEnabled = registrationEnabled;
        this.registration = new TransactionTemplate(transactions);
        this.registration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    // Validate the password, normalize the email, and save only its BCrypt hash.
    public AuthDtos.Accepted register(AuthDtos.Registration input) {
        if (!registrationEnabled) throw new ApiException(HttpStatus.FORBIDDEN, "Registration is disabled for this workspace.");
        PasswordPolicy.validate(input.password());
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        try {
            return registration.execute(status -> createAccount(input, email));
        } catch (DataIntegrityViolationException conflict) {
            // Read after rollback: a simultaneous winner is accepted without changing its account.
            if (isUniqueViolation(conflict) && users.existsByEmail(email)) return accepted();
            throw conflict;
        }
    }

    private AuthDtos.Accepted createAccount(AuthDtos.Registration input, String email) {
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

    private boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private AuthDtos.Accepted accepted() {
        return new AuthDtos.Accepted("VERIFICATION_REQUEST_ACCEPTED",
                "If this address can receive verification mail, a message will arrive shortly.");
    }
}
