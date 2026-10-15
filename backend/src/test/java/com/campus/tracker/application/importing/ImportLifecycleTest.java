package com.campus.tracker.application.importing;

import com.campus.tracker.application.ApplicationService;
import com.campus.tracker.application.ApplicationStatus;
import com.campus.tracker.application.ApplicationRules;
import com.campus.tracker.application.StageState;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import static com.campus.tracker.application.ApplicationDtos.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"debug=false","logging.level.root=WARN","campus.imports.cleanup-enabled=false"})
@ActiveProfiles("test")
class ImportLifecycleTest {
    @Autowired ImportPreviewService previews;
    @Autowired ImportCommitService commits;
    @Autowired ImportStatusService statuses;
    @Autowired ImportCleanupWorker cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ApplicationService applications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;

    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock mutableImportClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.now());
        volatile java.util.function.Supplier<Instant> reads;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { var source=reads;return source==null?time.get():source.get(); }
    }
    @BeforeEach void resetClock() { clock.reads=null;clock.time.set(Instant.now()); }

    @Test void duplicateCommitsReturnOriginalReceiptAndErasePayload() throws Exception {
        UUID owner = account();
        Preview preview = preview(owner,null);
        Receipt first = commits.commit(owner,preview.id(),request(preview));
        Receipt duplicate = commits.commit(owner,preview.id(),new CommitRequest("different",new Acknowledgement(99)));
        assertThat(duplicate).isEqualTo(first);
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(1);
        assertThat(statuses.status(owner,preview.id()).receipt()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select payload_bytes from application_imports where id=?",Long.class,preview.id())).isZero();
        assertThat(jdbc.queryForObject("select canonical_payload from application_imports where id=?",String.class,preview.id())).isNull();
    }

    @Test void supersessionCreditsReservationAndPreservesLostSuccessorLookup() throws Exception {
        UUID owner = account();
        Preview previous = preview(owner,null);
        for (int i=0;i<7;i++) {
            Preview successor = preview(owner,previous.id());
            assertThat(statuses.status(owner,previous.id()).successorId()).isEqualTo(successor.id());
            Preview retired = previous;
            assertThatThrownBy(() -> commits.commit(owner,retired.id(),request(retired)))
                    .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("IMPORT_PREVIEW_SUPERSEDED"));
            previous = successor;
        }
        assertThat(jdbc.queryForObject("select count(*) from application_imports where owner_id=? and state='PREVIEW'",Integer.class,owner)).isEqualTo(1);
    }

    @Test void staleOrForeignTargetsNeverWriteAnyRows() throws Exception {
        UUID owner = account();
        ApplicationView app = applications.create(owner,new ApplicationRequest("Old","Engineer","",ApplicationStatus.SAVED,null,null,null,"",List.of()));
        String csv = "ID,Company\n"+app.id()+",New\n";
        String options = json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","company"),"ISO",List.of(),null));
        Preview preview = previews.create(owner,csv.getBytes(StandardCharsets.UTF_8),options);
        applications.update(owner,app.id(),"0",new ApplicationRequest("Changed","Engineer","",ApplicationStatus.SAVED,null,null,null,"",List.of()));
        assertThatThrownBy(() -> commits.commit(owner,preview.id(),request(preview)))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("IMPORT_TARGET_CHANGED"));
        assertThat(applications.get(owner,app.id()).company()).isEqualTo("Changed");
        assertThat(statuses.status(owner,preview.id()).status()).isEqualTo("PREVIEW");
        assertThatThrownBy(() -> statuses.status(account(),preview.id())).isInstanceOf(ApiException.class);
    }

    @Test void expiryAndPolicyChangesRejectWithoutReleasingReservationUntilCleanup() throws Exception {
        UUID owner = account();
        Preview preview = preview(owner,null);
        jdbc.update("update application_imports set expires_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),preview.id());
        assertThatThrownBy(() -> commits.commit(owner,preview.id(),request(preview)))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("IMPORT_PREVIEW_EXPIRED"));
        assertThat(jdbc.queryForObject("select payload_bytes from application_imports where id=?",Long.class,preview.id())).isPositive();
        cleanup.cleanup();
        assertThatThrownBy(() -> statuses.status(owner,preview.id())).isInstanceOf(ApiException.class);
        Preview changed = preview(owner,null);
        jdbc.update("update application_imports set policy_version='unknown' where id=?",changed.id());
        assertThatThrownBy(() -> commits.commit(owner,changed.id(),request(changed)))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("IMPORT_POLICY_CHANGED"));
    }

    @Test void expiredStatusAndCleanupCannotRetireALockedCommitReservation() throws Exception {
        UUID owner=account(); Preview preview=preview(owner,null);
        jdbc.update("update application_imports set expires_at=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),preview.id());
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> held=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.queryForObject("select id from application_imports where id=? for update",UUID.class,preview.id());
            locked.countDown();
            try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException e) { throw new IllegalStateException(e); }
        }));
        try {
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(statuses.status(owner,preview.id()).status()).isEqualTo("UNRESOLVED");
            cleanup.cleanup();
            assertThat(jdbc.queryForObject("select payload_bytes from application_imports where id=?",Long.class,preview.id())).isPositive();
        } finally { release.countDown();held.get(10,TimeUnit.SECONDS);pool.shutdownNow(); }
    }

    @Test void admittedCommitCrossingExpiryStillPublishesOriginalReceipt() throws Exception {
        UUID owner=account();Preview preview=preview(owner,null);
        org.mockito.Mockito.doAnswer(call->{ clock.time.set(preview.expiresAt().plusSeconds(1));return call.callRealMethod(); })
                .when(applications).create(org.mockito.ArgumentMatchers.eq(owner),org.mockito.ArgumentMatchers.any(ApplicationRequest.class));
        Receipt receipt=commits.commit(owner,preview.id(),request(preview));
        assertThat(receipt.completedAt()).isAfter(preview.expiresAt());
        assertThat(statuses.status(owner,preview.id()).receipt()).isEqualTo(receipt);
        cleanup.cleanup();
        assertThat(commits.commit(owner,preview.id(),request(preview))).isEqualTo(receipt);
    }

    @Test void unlockedStatusCrossingExpiryCannotDeclareTerminalExpiry() throws Exception {
        UUID owner=account();Preview preview=preview(owner,null);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        clock.reads=()->calls.getAndIncrement()==0?preview.expiresAt().minusNanos(1):preview.expiresAt().plusNanos(1);
        assertThat(statuses.status(owner,preview.id()).status()).isEqualTo("PREVIEW");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void exactAcknowledgementCountIsMandatoryEvenWhenNoUpdates() throws Exception {
        UUID owner=account();Preview preview=preview(owner,null);
        assertThatThrownBy(()->commits.commit(owner,preview.id(),new CommitRequest(preview.reviewDigest(),new Acknowledgement(null))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_INVALID_COMMIT"));
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isZero();
    }

    @Test void secondWriteFailureRollsBackApplicationsAndReceipt() throws Exception {
        UUID owner=account();
        Preview preview=previews.create(owner,"Company,Role\nFirst,Engineer\nSecond,Engineer\n".getBytes(StandardCharsets.UTF_8),
                json.writeValueAsString(new Options(1,Map.of("0","company","1","role"),"ISO",List.of(),null)));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call->{if(count.incrementAndGet()==2) throw new IllegalStateException("injected write failure");return call.callRealMethod();})
                .when(applications).create(org.mockito.ArgumentMatchers.eq(owner),org.mockito.ArgumentMatchers.any(ApplicationRequest.class));
        assertThatThrownBy(()->commits.commit(owner,preview.id(),request(preview))).isInstanceOf(IllegalStateException.class);
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isZero();
        assertThat(statuses.status(owner,preview.id()).status()).isEqualTo("PREVIEW");
    }

    @Test void concurrentAdmissionsDoNotOverbookFiveDurableReservations() throws Exception {
        UUID owner=account();ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        List<Future<Boolean>> attempts=new ArrayList<>();
        try {
            for(int i=0;i<8;i++) attempts.add(pool.submit(()->{start.await();try {preview(owner,null);return true;}catch(org.springframework.dao.DataAccessException|ApiException rejected){return false;}}));
            start.countDown();int admitted=0;for(var attempt:attempts) if(attempt.get(15,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isBetween(1,5);
            assertThat(jdbc.queryForObject("select count(*) from application_imports where owner_id=? and state='PREVIEW'",Long.class,owner)).isEqualTo(admitted);
            while(admitted<5) {preview(owner,null);admitted++;}
            assertThatThrownBy(()->preview(owner,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_STORAGE_LIMIT"));
        } finally {pool.shutdownNow();}
    }

    @Test void maximumFiveHundredRowsAddUpdateAndUnchangedCommitAsWholeBatches() throws Exception {
        UUID owner=account();StringBuilder additions=new StringBuilder("Company,Role\n");
        for(int i=0;i<500;i++) additions.append("Company ").append(i).append(",Engineer\n");
        String options=json.writeValueAsString(new Options(1,Map.of("0","company","1","role"),"ISO",List.of(),null));
        Preview add=previews.create(owner,additions.toString().getBytes(StandardCharsets.UTF_8),options);
        assertThat(add.counts().added()).isEqualTo(500);
        Receipt created=commits.commit(owner,add.id(),request(add));
        assertThat(created.rows()).hasSize(500);
        StringBuilder updates=new StringBuilder("ID,Company\n");
        for(int i=0;i<created.rows().size();i++) updates.append(created.rows().get(i).applicationId()).append(",Updated ").append(i).append('\n');
        options=json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","company"),"ISO",List.of(),null));
        Preview update=previews.create(owner,updates.toString().getBytes(StandardCharsets.UTF_8),options);
        assertThat(update.counts().updated()).isEqualTo(500);
        Receipt updated=commits.commit(owner,update.id(),request(update));
        assertThat(updated.rows()).allSatisfy(row->assertThat(row.resultVersion()).isEqualTo(1));
        Preview unchanged=previews.create(owner,updates.toString().getBytes(StandardCharsets.UTF_8),options);
        assertThat(unchanged.counts().unchanged()).isEqualTo(500);
        Receipt noWrites=commits.commit(owner,unchanged.id(),request(unchanged));
        assertThat(noWrites.rows()).allSatisfy(row->assertThat(row.resultVersion()).isEqualTo(1));
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(500);
    }

    @Test void concurrentSameIdCommitsProduceOneOriginalReceipt() throws Exception {
        UUID owner=account();Preview preview=preview(owner,null);
        var outcomes=race(()->commits.commit(owner,preview.id(),request(preview)),()->commits.commit(owner,preview.id(),request(preview)));
        Receipt original=statuses.status(owner,preview.id()).receipt();
        assertThat(original).isNotNull();
        for(Object outcome:outcomes) if(outcome instanceof Receipt receipt) assertThat(receipt).isEqualTo(original); else assertThat(outcome).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(commits.commit(owner,preview.id(),request(preview))).isEqualTo(original);
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(1);
    }

    @Test void supersessionRacingCommitCannotEraseACommittingImport() throws Exception {
        UUID owner=account();Preview preview=preview(owner,null);
        var outcomes=race(()->commits.commit(owner,preview.id(),request(preview)),()->preview(owner,preview.id()));
        for(Object outcome:outcomes) if(outcome instanceof Exception rejected) assertThat(rejected).isInstanceOfAny(ApiException.class,org.springframework.dao.DataAccessException.class);
        Status state=statuses.status(owner,preview.id());
        if(state.status().equals("COMPLETED")) {
            assertThat(state.receipt()).isNotNull();
            assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(1);
        } else {
            assertThat(state.status()).isEqualTo("SUPERSEDED");
            assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isZero();
            Preview successor=statuses.status(owner,state.successorId()).preview();
            assertThat(commits.commit(owner,successor.id(),request(successor)).counts().added()).isEqualTo(1);
        }
    }

    @Test void persistedStageReplacementAdvancesParentAndKeepPreservesNewIdentities() throws Exception {
        UUID owner=account();
        ApplicationView original=applications.create(owner,new ApplicationRequest("Stages","Engineer","Remote",ApplicationStatus.SAVED,null,null,null,"Original notes",
                List.of(new StageRequest(null,"Phone",null,StageState.PLANNED),new StageRequest(null,"Technical",null,StageState.COMPLETED))));
        Set<UUID> originalIds=new HashSet<>(original.stages().stream().map(StageView::id).toList());
        // Reordering otherwise identical stage content is a replacement, not Unchanged.
        String replacement="[{\"name\":\"Technical\",\"date\":null,\"state\":\"Completed\"},{\"name\":\"Phone\",\"date\":null,\"state\":\"Planned\"}]";
        Preview stageOnly=stagePreview(owner,original.id(),replacement);
        assertThat(stageOnly.counts().updated()).isEqualTo(1);
        assertThat(stageOnly.rows().get(0).differences()).extracting(Difference::field).containsExactly("stages");
        Receipt replacementReceipt=commits.commit(owner,stageOnly.id(),request(stageOnly));
        assertThat(replacementReceipt.rows().get(0).resultVersion()).isEqualTo(1);
        ApplicationView replaced=applications.get(owner,original.id());
        assertThat(replaced.version()).isEqualTo(1);
        assertThat(replaced.stages()).extracting(StageView::name).containsExactly("Technical","Phone");
        assertThat(replaced.stages()).extracting(StageView::state).containsExactly(StageState.COMPLETED,StageState.PLANNED);
        assertThat(replaced.stages()).allSatisfy(stage->{assertThat(stage.id()).isNotNull();assertThat(originalIds).doesNotContain(stage.id());});
        assertThat(replaced.notes()).isEqualTo(original.notes());

        Preview unrelated=previews.create(owner,("ID,Notes,Stages\n"+original.id()+",Changed notes,[]\n").getBytes(StandardCharsets.UTF_8),
                json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","notes","2","stages"),"ISO",List.of(),null)));
        assertThat(unrelated.rows().get(0).intents().get("stages").intent()).isEqualTo("KEEP");
        assertThat(unrelated.rows().get(0).after().stages()).isEqualTo(ApplicationRules.fromView(replaced).stages());
        commits.commit(owner,unrelated.id(),request(unrelated));
        ApplicationView kept=applications.get(owner,original.id());
        assertThat(kept.version()).isEqualTo(2);
        assertThat(kept.notes()).isEqualTo("Changed notes");
        assertThat(kept.stages()).isEqualTo(replaced.stages());

        Preview identical=stagePreview(owner,original.id(),replacement);
        assertThat(identical.counts().unchanged()).isEqualTo(1);
        assertThat(commits.commit(owner,identical.id(),request(identical)).rows().get(0).resultVersion()).isEqualTo(2);
        assertThat(applications.get(owner,original.id())).isEqualTo(kept);
    }

    @Test void competingOrdinaryStageEditRejectsWholeImportIncludingAnotherAdd() throws Exception {
        UUID owner=account();
        ApplicationView original=applications.create(owner,new ApplicationRequest("Existing stages","Engineer","",ApplicationStatus.SAVED,null,null,null,"",
                List.of(new StageRequest(null,"Screen",null,StageState.PLANNED))));
        String replacement="[{\"name\":\"Imported panel\",\"date\":null,\"state\":\"Planned\"}]";
        String csv="ID,Company,Role,Stages\n,Would be added,Engineer,[]\n"+original.id()+",,,"+csvCell(replacement)+"\n";
        Preview preview=previews.create(owner,csv.getBytes(StandardCharsets.UTF_8),json.writeValueAsString(new Options(1,
                Map.of("0","applicationId","1","company","2","role","3","stages"),"ISO",
                List.of(new RowOverride(2,null,null,Map.of(),List.of(),"REPLACE",false)),null)));
        assertThat(preview.counts().added()).isEqualTo(1);
        assertThat(preview.counts().updated()).isEqualTo(1);
        UUID stageId=original.stages().get(0).id();
        ApplicationView edited=applications.update(owner,original.id(),"0",new ApplicationRequest(original.company(),original.role(),original.location(),original.status(),null,null,null,original.notes(),
                List.of(new StageRequest(stageId,"Ordinary concurrent edit",null,StageState.COMPLETED))));
        assertThat(edited.version()).isEqualTo(1);
        assertThatThrownBy(()->commits.commit(owner,preview.id(),request(preview))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_TARGET_CHANGED"));
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(1);
        assertThat(applications.get(owner,original.id())).isEqualTo(edited);
        assertThat(applications.get(owner,original.id()).stages().get(0).id()).isEqualTo(stageId);
        assertThat(statuses.status(owner,preview.id()).status()).isEqualTo("PREVIEW");
        assertThat(jdbc.queryForObject("select count(*) from application_imports where id=? and receipt is not null",Long.class,preview.id())).isZero();
    }

    @Test void completedReceiptsExpireAfterThirtyDaysUnderLockAndCleanupPreservesNeighbor() throws Exception {
        UUID owner=account();Preview old=preview(owner,null);Receipt expired=commits.commit(owner,old.id(),request(old));
        assertThat(expired.retainUntil()).isEqualTo(expired.completedAt().plus(Duration.ofDays(30)));
        clock.time.set(expired.completedAt().plus(Duration.ofDays(1)));
        Preview newer=preview(owner,null);Receipt retained=commits.commit(owner,newer.id(),request(newer));
        clock.time.set(expired.retainUntil().minusNanos(1));
        assertThat(statuses.status(owner,old.id()).receipt()).isEqualTo(expired);
        assertThat(commits.commit(owner,old.id(),request(old))).isEqualTo(expired);
        clock.time.set(expired.retainUntil());
        whileImportLocked(old.id(),()->{
            assertThat(statuses.status(owner,old.id()).status()).isEqualTo("UNRESOLVED");
            cleanup.cleanup();
            assertThat(jdbc.queryForObject("select count(*) from application_imports where id=?",Long.class,old.id())).isEqualTo(1);
        });
        assertThatThrownBy(()->statuses.status(owner,old.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_NOT_FOUND"));
        assertThatThrownBy(()->commits.commit(owner,old.id(),request(old))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_NOT_FOUND"));
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from application_imports where id=?",Long.class,old.id())).isZero();
        assertThat(statuses.status(owner,newer.id()).receipt()).isEqualTo(retained);
        assertThat(commits.commit(owner,newer.id(),request(newer))).isEqualTo(retained);
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(2);
    }

    @Test void corruptedCanonicalReviewAndUnsupportedSchemaRejectWithoutWrites() throws Exception {
        UUID owner=account();Preview corrupt=preview(owner,null);
        String payload=jdbc.queryForObject("select canonical_payload from application_imports where id=?",String.class,corrupt.id());
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("preview").path("rows").get(0).path("after")).put("company","Altered persisted review");
        String altered=json.writeValueAsString(tree);
        jdbc.update("update application_imports set canonical_payload=?,payload_bytes=? where id=?",altered,altered.getBytes(StandardCharsets.UTF_8).length,corrupt.id());
        assertThatThrownBy(()->commits.commit(owner,corrupt.id(),request(corrupt))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_INTEGRITY_FAILED"));
        assertThat(statuses.status(owner,corrupt.id()).status()).isEqualTo("PREVIEW");
        Preview unsupported=preview(owner,null);
        jdbc.update("update application_imports set schema_version=2 where id=?",unsupported.id());
        assertThatThrownBy(()->commits.commit(owner,unsupported.id(),request(unsupported))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_POLICY_CHANGED"));
        assertThat(statuses.status(owner,unsupported.id()).preview().id()).isEqualTo(unsupported.id());
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application_imports where owner_id=? and state='COMPLETED'",Long.class,owner)).isZero();
        assertThatThrownBy(()->statuses.status(account(),corrupt.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_NOT_FOUND"));
    }

    @Test void differentPreviewsRacingSameTargetVersionCanCommitOnlyOnce() throws Exception {
        UUID owner=account();ApplicationView original=applications.create(owner,new ApplicationRequest("Original","Engineer","",ApplicationStatus.SAVED,null,null,null,"",List.of()));
        String options=json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","company"),"ISO",List.of(),null));
        Preview first=previews.create(owner,("ID,Company\n"+original.id()+",First contender\n").getBytes(StandardCharsets.UTF_8),options);
        Preview second=previews.create(owner,("ID,Company\n"+original.id()+",Second contender\n").getBytes(StandardCharsets.UTF_8),options);
        assertThat(first.rows().get(0).targetVersion()).isZero();assertThat(second.rows().get(0).targetVersion()).isZero();
        List<Object> outcomes=race(()->commits.commit(owner,first.id(),request(first)),()->commits.commit(owner,second.id(),request(second)));
        assertThat(outcomes.stream().filter(Receipt.class::isInstance).count()).isEqualTo(1);
        int winnerIndex=outcomes.get(0) instanceof Receipt?0:1;
        Preview winner=winnerIndex==0?first:second,loser=winnerIndex==0?second:first;
        Object rejected=outcomes.get(1-winnerIndex);
        if(rejected instanceof ApiException failure) assertThat(failure.getCode()).isEqualTo("IMPORT_TARGET_CHANGED");
        else assertThat(rejected).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->commits.commit(owner,loser.id(),request(loser))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_TARGET_CHANGED"));
        assertThat(statuses.status(owner,winner.id()).receipt()).isEqualTo(outcomes.get(winnerIndex));
        assertThat(statuses.status(owner,loser.id()).status()).isEqualTo("PREVIEW");
        ApplicationView result=applications.get(owner,original.id());
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.company()).isEqualTo(winner.rows().get(0).after().company());
        assertThat(applications.list(owner,"",null,0,100,"updated").totalElements()).isEqualTo(1);
    }

    private Preview stagePreview(UUID owner,UUID target,String stages) throws Exception {
        return previews.create(owner,("ID,Stages\n"+target+","+csvCell(stages)+"\n").getBytes(StandardCharsets.UTF_8),
                json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","stages"),"ISO",
                        List.of(new RowOverride(1,null,null,Map.of(),List.of(),"REPLACE",false)),null)));
    }
    private String csvCell(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    private void whileImportLocked(UUID id,Runnable action) throws Exception {
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> held=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.queryForObject("select id from application_imports where id=? for update",UUID.class,id);locked.countDown();
            try {assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}
        }));
        try {assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();action.run();}
        finally {release.countDown();held.get(15,TimeUnit.SECONDS);pool.shutdownNow();}
    }

    private List<Object> race(Callable<?> first,Callable<?> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Object>> futures=new ArrayList<>();
            for(Callable<?> action:List.of(first,second)) futures.add(pool.submit(()->{start.await();try{return action.call();}catch(Exception e){return e;}}));
            start.countDown();return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }

    private Preview preview(UUID owner,UUID predecessor) throws Exception {
        return previews.create(owner,"Company,Role\nAcme,Engineer\n".getBytes(StandardCharsets.UTF_8),json.writeValueAsString(
                new Options(1,Map.of("0","company","1","role"),"ISO",List.of(),predecessor)));
    }
    private CommitRequest request(Preview preview) { return new CommitRequest(preview.reviewDigest(),new Acknowledgement(preview.counts().updated())); }
    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campus_users (id,email,display_name,password_hash,created_at) values (?,?,?,?,?)",id,id+"@example.test","Import test","unused",java.sql.Timestamp.from(Instant.now()));
        return id;
    }
}
