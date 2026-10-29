package com.campus.tracker.application.importing;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.UUID;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static com.campus.tracker.application.importing.ImportPreviewService.error;

@Service
public class ImportStatusService {
    private final ImportPreviewRepository repository;
    private final ImportCanonicalizer canonical;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ImportStatusService(ImportPreviewRepository repository,ImportCanonicalizer canonical,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.canonical=canonical;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(2);
    }
    public Status status(UUID owner,UUID id) {
        var row=repository.find(owner,id,false);
        boolean terminalExpired=row.state().equals("COMPLETED")?!clock.instant().isBefore(row.retain()):!clock.instant().isBefore(row.expires());
        if(!terminalExpired) return response(row,false);
        try {
            // Reread under the commit lock. An in-flight commit crossing TTL can still publish its receipt.
            return transaction.execute(tx->response(repository.find(owner,id,true),true));
        } catch(DataAccessException e) { return new Status("UNRESOLVED",null,null,null,"IMPORT_STATUS_UNRESOLVED"); }
    }
    private Status response(ImportPreviewRepository.Stored row,boolean locked) {
        if(row.state().equals("COMPLETED")) {
            if(locked && !clock.instant().isBefore(row.retain())) throw error(HttpStatus.NOT_FOUND,"IMPORT_NOT_FOUND","Import not found.");
            return new Status("COMPLETED",null,canonical.read(row.receipt(),Receipt.class),null,null);
        }
        if(locked && !clock.instant().isBefore(row.expires())) throw error(HttpStatus.GONE,"IMPORT_PREVIEW_EXPIRED","The preview expired. Create a fresh preview.");
        if(row.state().equals("SUPERSEDED")) return new Status("SUPERSEDED",null,null,row.successor(),null);
        return new Status("PREVIEW",canonical.read(row.payload(),CanonicalPreview.class).preview(),null,null,null);
    }
}
