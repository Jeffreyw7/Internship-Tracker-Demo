package com.campus.tracker.auth.reset;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ResetControl {
    private final JdbcTemplate jdbc;
    public ResetControl(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean enabled() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM password_reset_control WHERE id=1",Boolean.class));
    }
    public void requireEnabled(boolean intake) {
        Boolean allowed = jdbc.queryForObject(intake
                ? "SELECT enabled AND NOT operator_intake_paused AND NOT health_intake_paused FROM password_reset_control WHERE id=1"
                : "SELECT enabled FROM password_reset_control WHERE id=1",Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw unavailable();
    }
    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RECOVERY_UNAVAILABLE","Password recovery is temporarily unavailable. Please try again later.");
    }
}
