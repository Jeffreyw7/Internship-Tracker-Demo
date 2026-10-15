package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

/** Expiry alone never releases a reservation: cleanup must first acquire the same row lock as commit. */
@Component
public class ImportCleanupWorker {
    private final ImportPreviewRepository repository;
    private final ImportAdmissionService admission;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ImportCleanupWorker(ImportPreviewRepository repository,ImportAdmissionService admission,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.admission=admission;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(2);
    }
    @Scheduled(fixedDelayString="${campus.imports.cleanup-delay-ms:60000}")
    public void cleanup() {
        for(var candidate:repository.expiredCandidates(clock.instant())) {
            try {
                transaction.executeWithoutResult(tx->{
                    admission.lock(candidate.owner());
                    var row=repository.find(candidate.owner(),candidate.id(),true);
                    if(row.state().equals("COMPLETED")?!clock.instant().isBefore(row.retain()):!clock.instant().isBefore(row.expires())) repository.delete(row);
                    admission.retireUnusedOwnerLocks();
                });
            } catch(DataAccessException | ApiException ignored) { /* Busy commits and rows already purged are retried next sweep. */ }
        }
        try {
            transaction.executeWithoutResult(tx->{admission.globalLock();admission.retireUnusedOwnerLocks();});
        } catch(DataAccessException ignored) { /* Admission holds the lock; the next sweep performs idle-key cleanup. */ }
    }
}
