package com.campus.tracker.auth.reset;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit local-only switch for capture-mail demos; production activation stays operator-owned. */
@Component
@Profile("!prod")
@ConditionalOnProperty(name="campus.reset.development-enabled",havingValue="true")
public final class ResetDevelopmentSetup {
    private final JdbcTemplate jdbc;
    public ResetDevelopmentSetup(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct void enable() { jdbc.update("UPDATE password_reset_control SET enabled=TRUE WHERE id=1"); }
}
