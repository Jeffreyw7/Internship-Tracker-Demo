package com.campus.tracker.auth.reset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Clock;

/** Wall-clock time from the write primary, including after row-lock waits. */
@Component
public final class DatabaseTime {
    private final JdbcTemplate jdbc;
    private final boolean postgres;
    private final Clock clock;
    public DatabaseTime(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc; this.clock=clock;
        this.postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }
    public Instant now() {
        // Embedded H2 shares this process's clock; its CURRENT_TIMESTAMP is transaction-scoped.
        // Production PostgreSQL must use the primary's wall clock instead of application time.
        return (postgres ? jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant()) : clock.instant())
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public boolean isPostgres() { return postgres; }
    public long nextOrder() {
        return jdbc.queryForObject("SELECT nextval('auth_security_event_order')", Long.class);
    }
}
