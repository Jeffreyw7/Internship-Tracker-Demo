package com.campus.tracker.auth.reset;

import com.campus.tracker.auth.abuse.AuthThrottleException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.ArrayList;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResetBackgroundAdmissionTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final Clock clock=mock(Clock.class);
    private final DatabaseTime time=new DatabaseTime(jdbc,clock);
    @BeforeEach void clearMetadataProbe() { clearInvocations(jdbc); }

    private Runnable task(boolean health,RecoveryCapacity capacity) {
        if (health) return new ResetHealth(jdbc,time,mock(PasswordResetRequestWorker.class),
                mock(ResetMaintenance.class),capacity,true)::check;
        return new ResetMaintenance(jdbc,time,capacity,mock(PlatformTransactionManager.class))::run;
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void saturationDoesNotStartAnUnadmittedFallbackWrite(boolean health) {
        var capacity=new RecoveryCapacity();
        var permits=new ArrayList<RecoveryCapacity.Permit>();
        try {
            for (int i=0;i<6;i++) permits.add(capacity.database());
            task(health,capacity).run();
            verifyNoInteractions(jdbc,clock);
        } finally { permits.forEach(RecoveryCapacity.Permit::close); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void databaseFailureReacquiresAdmissionBeforePausingIntake(boolean health) {
        var capacity=new RecoveryCapacity();
        when(clock.instant()).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        doAnswer(invocation->{
            var held=new ArrayList<RecoveryCapacity.Permit>();
            try {
                for (int i=0;i<5;i++) held.add(capacity.database());
                assertThatThrownBy(()->{ try(var unexpected=capacity.database()) { } })
                        .as("The fallback JDBC write must itself hold the sixth permit")
                        .isInstanceOf(AuthThrottleException.class);
            } finally { held.forEach(RecoveryCapacity.Permit::close); }
            return 1;
        }).when(jdbc).update(anyString());
        task(health,capacity).run();
        verify(jdbc).update("UPDATE password_reset_control SET health_intake_paused=TRUE WHERE id=1");
        // Both the original and fallback permit are returned.
        var permits=new ArrayList<RecoveryCapacity.Permit>();
        try { for (int i=0;i<6;i++) permits.add(capacity.database()); }
        finally { permits.forEach(RecoveryCapacity.Permit::close); }
    }

}
