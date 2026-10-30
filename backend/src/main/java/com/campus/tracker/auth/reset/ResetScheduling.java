package com.campus.tracker.auth.reset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="campus.reset.worker-enabled",havingValue="true",matchIfMissing=true)
public class ResetScheduling {
    private final PasswordResetRequestWorker worker;
    private final ResetMaintenance maintenance;
    public ResetScheduling(PasswordResetRequestWorker worker,ResetMaintenance maintenance) { this.worker=worker;this.maintenance=maintenance; }
    @Scheduled(fixedDelay=1000) public void intake() { worker.processPending(); }
    @Scheduled(fixedDelay=1000,initialDelay=500) public void secondIntake() { worker.processPending(); }
    @Scheduled(fixedDelay=60000,initialDelay=1000) public void maintain() { maintenance.run(); }
}
