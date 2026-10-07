package com.campus.tracker.auth;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class UserPreferencesService {
    private final UserRepository users;
    public UserPreferencesService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public String read(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account is unavailable.")).getThemePreference();
    }

    @Transactional
    public String write(UUID userId, String preference) {
        if (users.updateThemePreference(userId, preference) != 1)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Account is unavailable.");
        return preference;
    }
}
