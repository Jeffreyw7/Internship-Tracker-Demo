package com.campus.tracker.auth;

import com.campus.tracker.application.SampleDataService;
import com.campus.tracker.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class AccountService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SampleDataService samples;
    private final boolean registrationEnabled;
    public AccountService(UserRepository users, PasswordEncoder encoder, SampleDataService samples,
                          @Value("${campus.registration-enabled}") boolean registrationEnabled) {
        this.users = users; this.encoder = encoder; this.samples = samples; this.registrationEnabled = registrationEnabled;
    }
    @Transactional
    public AuthDtos.UserView register(AuthDtos.Registration input) {
        if (!registrationEnabled) throw new ApiException(HttpStatus.FORBIDDEN, "Registration is disabled for this workspace.");
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes.");
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists. Sign in instead.");
        CampusUser user = users.saveAndFlush(new CampusUser(email, input.displayName().trim(), encoder.encode(input.password())));
        if (input.includeSamples()) samples.seed(user.getId());
        return new AuthDtos.UserView(user.getId(), user.getDisplayName(), user.getEmail());
    }
}
