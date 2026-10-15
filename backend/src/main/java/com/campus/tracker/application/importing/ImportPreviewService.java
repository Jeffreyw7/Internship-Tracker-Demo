package com.campus.tracker.application.importing;

import com.campus.tracker.application.ApplicationService;
import com.campus.tracker.application.ApplicationDtos.ApplicationView;
import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.campus.tracker.application.importing.ImportDtos.*;

@Service
public class ImportPreviewService {
    public static final String POLICY_VERSION="csv-import-1";
    private final CsvImportParser parser;
    private final ImportSemantics semantics;
    private final ApplicationService applications;
    private final ImportCanonicalizer canonical;
    private final ImportPreviewRepository repository;
    private final ImportAdmissionService admission;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ImportPreviewService(CsvImportParser parser,ImportSemantics semantics,ApplicationService applications,ImportCanonicalizer canonical,
            ImportPreviewRepository repository,ImportAdmissionService admission,Clock clock,PlatformTransactionManager manager) {
        this.parser=parser;this.semantics=semantics;this.applications=applications;this.canonical=canonical;this.repository=repository;this.admission=admission;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }
    public Preview create(UUID owner,byte[] file,String optionsJson) {
        ImportUploadPolicy.checkCurrentDeadline();
        var csv=parser.parse(file);
        if(optionsJson!=null && ImportCanonicalizer.bytes(optionsJson)>256*1024) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_SIZE_LIMIT","Import options exceed their size limit.");
        Options options=semantics.validateOptions(optionsJson==null || optionsJson.isBlank()?new Options(1,Map.of(),"ISO",List.of(),null):canonical.read(optionsJson,Options.class),csv.headers().size(),csv.records().size());
        ImportUploadPolicy.checkCurrentDeadline();
        List<ApplicationView> snapshots=transaction.execute(tx->{
            Set<UUID> targets=new HashSet<>();
            Map<Integer,RowOverride> overrides=new HashMap<>();options.rows().forEach(r->overrides.put(r.rowNumber(),r));
            Set<List<String>> candidateKeys=new LinkedHashSet<>();
            for(int index=0;index<csv.records().size();index++) {
                RowOverride correction=overrides.get(index+1);
                String id=mappedValue(csv.records().get(index),options,correction,"applicationId");
                if(!id.isEmpty()) { try { targets.add(UUID.fromString(id)); } catch(IllegalArgumentException ignored) {} }
                else if(correction!=null && correction.targetId()!=null) targets.add(correction.targetId());
                candidateKeys.add(List.of(mappedValue(csv.records().get(index),options,correction,"company").trim().toLowerCase(Locale.ROOT),mappedValue(csv.records().get(index),options,correction,"role").trim().toLowerCase(Locale.ROOT)));
            }
            ImportUploadPolicy.checkCurrentDeadline();
            Map<UUID,ApplicationView> result=new HashMap<>(applications.findForImport(owner,targets));
            // Candidates retain summaries only; full editable snapshots are held solely for <=500 explicit targets.
            for(List<String> key:candidateKeys) {
                if(key.get(0).isEmpty() || key.get(1).isEmpty()) continue;
                ImportUploadPolicy.checkCurrentDeadline();
                for(var a:applications.findImportCandidates(owner,key.get(0),key.get(1))) {
                    if(targets.contains(a.id())) continue;
                    result.putIfAbsent(a.id(),new ApplicationView(a.id(),a.company(),a.role(),a.location(),a.status(),"","","","",List.of(),a.createdAt(),a.updatedAt(),a.version(),a.sample(),a.deleted()));
                }
            }
            return List.copyOf(result.values());
        });
        List<PreviewRow> rows=semantics.project(csv,options,snapshots);
        List<Issue> issues=options.mapping().size()!=csv.headers().size()?List.of(new Issue("IMPORT_MAPPING_REQUIRED","mapping","Map every source column or explicitly ignore it.")):List.of();
        Counts counts=counts(rows);
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        Preview blank=new Preview(UUID.randomUUID(),1,POLICY_VERSION,now,now.plusSeconds(1800),"",issues.isEmpty() && counts.errors()==0,csv.headers(),rows,counts,issues);
        String digest=canonical.digest(canonical.write(new CanonicalPreview(blank,options,csv.sourceDigest()),ImportCanonicalizer.PAYLOAD_LIMIT));
        Preview preview=withDigest(blank,digest);
        String payload=canonical.write(new CanonicalPreview(preview,options,csv.sourceDigest()),ImportCanonicalizer.PAYLOAD_LIMIT);
        ImportUploadPolicy.checkCurrentDeadline();
        return transaction.execute(tx->{
            admission.lock(owner);
            ImportPreviewRepository.Stored predecessor=null;
            if(options.supersedesPreviewId()!=null) {
                predecessor=repository.find(owner,options.supersedesPreviewId(),true);
                if(predecessor.state().equals("COMPLETED")) throw error(HttpStatus.CONFLICT,"IMPORT_ALREADY_COMPLETED","The predecessor import already completed. Read its receipt.");
                if(predecessor.state().equals("SUPERSEDED")) throw error(HttpStatus.GONE,"IMPORT_PREVIEW_SUPERSEDED","This preview has already been superseded. Read its successor.");
                if(!predecessor.sourceDigest().equals(csv.sourceDigest())) throw error(HttpStatus.BAD_REQUEST,"IMPORT_SOURCE_MISMATCH","Corrections must use the original file.");
                if(!clock.instant().isBefore(predecessor.expires())) throw error(HttpStatus.GONE,"IMPORT_PREVIEW_EXPIRED","The predecessor preview expired.");
            }
            admission.requireCapacity(owner,ImportCanonicalizer.bytes(payload),predecessor);
            ImportUploadPolicy.checkCurrentDeadline();
            repository.insert(owner,preview,csv.sourceDigest(),payload);
            if(predecessor!=null) repository.supersede(predecessor,preview.id());
            return preview;
        });
    }
    static Counts counts(List<PreviewRow> rows) {
        int adds=0,updates=0,skips=0,unchanged=0,errors=0;
        for(var row:rows) {
            if(!row.issues().isEmpty() && !row.action().equals("Skip")) errors++;
            switch(row.action()) { case "Add"->adds++;case "Update"->updates++;case "Skip"->skips++;case "Unchanged"->unchanged++;default->throw new IllegalStateException(); }
        }
        return new Counts(adds,updates,skips,unchanged,errors);
    }
    private String mappedValue(List<String> cells,Options options,RowOverride correction,String field) {
        if(correction!=null && correction.values().containsKey(field)) return correction.values().get(field);
        for(var entry:options.mapping().entrySet()) if(entry.getValue().equals(field)) return cells.get(Integer.parseInt(entry.getKey()));
        return "";
    }
    static Preview withDigest(Preview p,String digest) { return new Preview(p.id(),p.schemaVersion(),p.policyVersion(),p.createdAt(),p.expiresAt(),digest,p.committable(),p.headers(),p.rows(),p.counts(),p.issues()); }
    static ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
}
