package com.campus.tracker.application.importing;

import com.campus.tracker.application.*;
import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static com.campus.tracker.application.importing.ImportPreviewService.error;

/** The metadata row, all application writes, and the original receipt share this outer transaction. */
@Service
public class ImportCommitService {
    private final ImportPreviewRepository repository;
    private final ImportCanonicalizer canonical;
    private final ApplicationService applications;
    private final ApplicationRules rules;
    private final Clock clock;
    public ImportCommitService(ImportPreviewRepository repository,ImportCanonicalizer canonical,ApplicationService applications,ApplicationRules rules,Clock clock) {
        this.repository=repository;this.canonical=canonical;this.applications=applications;this.rules=rules;this.clock=clock;
    }
    @Transactional(timeout=30)
    public Receipt commit(UUID owner,UUID id,CommitRequest request) {
        rulesForRequest(request);
        var stored=repository.find(owner,id,true);
        if(stored.state().equals("COMPLETED")) {
            if(!clock.instant().isBefore(stored.retain())) throw error(HttpStatus.NOT_FOUND,"IMPORT_NOT_FOUND","Import not found.");
            return canonical.read(stored.receipt(),Receipt.class);
        }
        if(stored.state().equals("SUPERSEDED")) throw error(HttpStatus.GONE,"IMPORT_PREVIEW_SUPERSEDED","This preview has been superseded. Read its successor.");
        checkExpiry(stored);
        if(stored.schema()!=1 || !stored.policy().equals(ImportPreviewService.POLICY_VERSION)) throw error(HttpStatus.CONFLICT,"IMPORT_POLICY_CHANGED","Import policy changed. Create a fresh preview.");
        var saved=canonical.read(stored.payload(),CanonicalPreview.class); var preview=saved.preview();
        String actual=canonical.digest(canonical.write(new CanonicalPreview(ImportPreviewService.withDigest(preview,""),saved.options(),saved.sourceDigest()),ImportCanonicalizer.PAYLOAD_LIMIT));
        if(!actual.equals(stored.reviewDigest()) || !preview.reviewDigest().equals(stored.reviewDigest()) || !saved.sourceDigest().equals(stored.sourceDigest())) throw error(HttpStatus.CONFLICT,"IMPORT_INTEGRITY_FAILED","The stored review failed its integrity check.");
        if(!preview.committable() || preview.counts().errors()!=0) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IMPORT_NOT_READY","Correct or exclude invalid rows and create a fresh preview.");
        if(!request.reviewDigest().equals(stored.reviewDigest())) throw error(HttpStatus.CONFLICT,"IMPORT_REVIEW_CHANGED","The review digest does not match this preview.");
        if(request.acknowledgement().existingApplicationsToUpdate()!=preview.counts().updated()) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IMPORT_ACK_REQUIRED","Acknowledge the exact existing application update count.");
        Set<UUID> targets=new HashSet<>();
        for(var row:preview.rows()) if(!row.action().equals("Skip") && row.targetId()!=null && !targets.add(row.targetId())) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IMPORT_DUPLICATE_TARGET","Multiple rows target the same application. Zero applications were committed.");
        Map<UUID,ApplicationView> current=applications.lockForImport(owner,targets);
        for(var row:preview.rows()) {
            if(row.action().equals("Skip")) continue;
            if(row.targetId()!=null) {
                ApplicationView target=current.get(row.targetId());
                if(target==null || !Objects.equals(row.targetVersion(),target.version()) || !ApplicationRules.fromView(target).equals(row.before()))
                    throw error(HttpStatus.CONFLICT,"IMPORT_TARGET_CHANGED","A selected application changed or is unavailable. Zero applications were committed.");
            }
            if(row.after()==null) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IMPORT_NOT_READY","The stored review has no valid result. Zero applications were committed.");
            try { rules.validate(row.after()); }
            catch(ApiException | jakarta.validation.ConstraintViolationException e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IMPORT_VALIDATION_FAILED","Application validation changed. Zero applications were committed."); }
        }
        // Admission is checked again after target locks. Completion may safely cross the expiry instant.
        checkExpiry(stored);
        List<ReceiptRow> results=new ArrayList<>();
        for(var row:preview.rows()) {
            ApplicationView result=null;
            if(row.action().equals("Add")) result=applications.create(owner,row.after());
            else if(row.action().equals("Update")) result=applications.update(owner,row.targetId(),Long.toString(row.targetVersion()),row.after());
            else if(row.action().equals("Unchanged")) result=current.get(row.targetId());
            results.add(new ReceiptRow(row.rowNumber(),row.action(),result==null?null:result.id(),result==null?null:result.version()));
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        Receipt receipt=new Receipt(id,UUID.randomUUID(),now,now.plus(30,ChronoUnit.DAYS),preview.counts(),List.copyOf(results));
        String serialized=canonical.write(receipt,ImportCanonicalizer.RECEIPT_LIMIT);
        repository.complete(stored,receipt,serialized);
        return receipt;
    }
    private void checkExpiry(ImportPreviewRepository.Stored stored) {
        if(!clock.instant().isBefore(stored.expires())) throw error(HttpStatus.GONE,"IMPORT_PREVIEW_EXPIRED","The preview expired. Create a fresh preview.");
    }
    private void rulesForRequest(CommitRequest request) {
        if(request==null || request.reviewDigest()==null || request.reviewDigest().isBlank() || request.reviewDigest().length()>64 || request.acknowledgement()==null || request.acknowledgement().existingApplicationsToUpdate()==null || request.acknowledgement().existingApplicationsToUpdate()<0 || request.acknowledgement().existingApplicationsToUpdate()>500)
            throw error(HttpStatus.BAD_REQUEST,"IMPORT_INVALID_COMMIT","The import confirmation is invalid.");
    }
}
