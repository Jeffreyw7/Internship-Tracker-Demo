package com.campus.tracker.auth.reset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import com.campus.tracker.auth.abuse.AuthThrottleException;

/** Separate health scheduling still pauses admission when intake or maintenance stops running. */
@Service
public class ResetHealth {
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    private final PasswordResetRequestWorker worker;
    private final ResetMaintenance maintenance;
    private final RecoveryCapacity capacity;
    private final boolean workerEnabled;
    public ResetHealth(JdbcTemplate jdbc,DatabaseTime time,PasswordResetRequestWorker worker,ResetMaintenance maintenance,
            RecoveryCapacity capacity,@Value("${campus.reset.worker-enabled:true}") boolean workerEnabled) {
        this.jdbc=jdbc;this.time=time;this.worker=worker;this.maintenance=maintenance;this.capacity=capacity;this.workerEnabled=workerEnabled;
    }
    public void check() {
        try (var database=capacity.database()) {
            Instant now=time.now();
            Instant oldest=jdbc.query("SELECT MIN(created_at) FROM password_reset_requests WHERE status IN ('PENDING','PROCESSING')",rs->{rs.next();var value=rs.getTimestamp(1);return value==null?null:value.toInstant();});
            boolean pause=!workerEnabled || !worker.healthy() || !maintenance.healthy() || (oldest!=null && oldest.isBefore(now.minusSeconds(120)));
            jdbc.update("UPDATE password_reset_control SET health_intake_paused=? WHERE id=1",pause);
        } catch (AuthThrottleException saturated) {
            // Defer until the next poll; even failure reporting must respect database admission.
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).error("Recovery health check failed category=DATABASE_OR_CAPACITY");
            try (var database=capacity.database()) {
                jdbc.update("UPDATE password_reset_control SET health_intake_paused=TRUE WHERE id=1");
            } catch (RuntimeException ignored) { /* Missing quota/primary state fails admission closed. */ }
        }
    }
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name="campus.reset.health-enabled",havingValue="true",matchIfMissing=true)
    static class Schedule {
        private final ResetHealth health;
        Schedule(ResetHealth health) { this.health=health; }
        @Scheduled(fixedDelay=10000,initialDelay=1000) public void check() { health.check(); }
    }
}
