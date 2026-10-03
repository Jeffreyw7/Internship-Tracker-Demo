package com.campus.tracker;

import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.mail.PermanentDeliveryException;
import com.campus.tracker.reminder.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static com.campus.tracker.reminder.ReminderDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"campus.reminders.worker-enabled=false","campus.reminders.mail-enabled=true",
    "campus.reminders.mail-from=campus@example.test","spring.mail.host=smtp.example.test"})
@ActiveProfiles("test")
class ReminderDeliveryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ReminderService reminders;
    @Autowired ReminderWorker worker;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired ReminderProperties config;
    @MockitoBean Clock clock;
    @MockitoBean ReminderEmailSender sender;
    UUID owner;
    @BeforeEach void setup() {
        at("2026-10-02T08:00:00Z");
        owner=users.saveAndFlush(new CampusUser("delivery-"+UUID.randomUUID()+"@example.test","Delivery Test","test-hash")).getId();
        reminders.overview(owner);
    }
    void at(String time) { when(clock.instant()).thenReturn(Instant.parse(time)); }
    @AfterEach void isolateDeliveryOwners() {
        jdbc.update("UPDATE reminder_preferences SET enabled=FALSE WHERE owner_id IN (SELECT id FROM campus_users WHERE display_name IN ('Delivery Test','Expiry Test','Page Test'))");
    }
    void enable(DeliveryMode mode,String timezone,String digest) {
        var p=new Preferences(true,true,true,true,1,3,0,timezone,"09:00",mode,digest,true);
        reminders.savePreferences(owner,""+reminders.overview(owner).preferences().version(),p);
    }
    UUID source(String date) {
        var id=UUID.randomUUID();var now=Timestamp.from(clock.instant());
        jdbc.update("""
            INSERT INTO applications(id,owner_id,company,role,location,status,applied_date,follow_up_date,notes,created_at,updated_at)
            VALUES(?,?,?,'Java Intern','','APPLIED',?,?,'',?,?)
            """,id,owner,"Delivery Labs",LocalDate.parse("2026-10-01"),LocalDate.parse(date),now,now);
        reminders.reconcile(owner);return id;
    }
    int jobs(String status) { return jdbc.queryForObject("SELECT COUNT(*) FROM reminder_deliveries WHERE owner_id=? AND status=?",Integer.class,owner,status); }
    void process() { reminders.processOwner(owner); }

    void expiringSources(String date) {
        var now=Timestamp.from(clock.instant());var deadline=UUID.randomUUID();var interview=UUID.randomUUID();
        jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,deadline,notes,created_at,updated_at) VALUES(?,?,'Deadline Labs','Intern','','SAVED',?,'',?,?)",
            deadline,owner,LocalDate.parse(date),now,now);
        jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,applied_date,notes,created_at,updated_at) VALUES(?,?,'Interview Labs','Intern','','INTERVIEWING',?,'',?,?)",
            interview,owner,LocalDate.parse("2026-10-01"),now,now);
        jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,0,'Technical interview',?,'SCHEDULED')",
            UUID.randomUUID(),interview,LocalDate.parse(date));
        reminders.reconcile(owner);
    }

    @Test void sameDayEventsDueAfterDigestNotifyBeforeExpiryInAccountTimezone() {
        reminders.savePreferences(owner,"0",new Preferences(true,true,true,true,0,0,0,"America/New_York","13:00",DeliveryMode.DAILY_DIGEST,"09:00",true));
        expiringSources("2026-10-02");source("2026-10-02");
        at("2026-10-02T13:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isZero();
        at("2026-10-02T16:59:00Z");worker.runOnce();assertThat(reminders.overview(owner).readyCount()).isZero();
        at("2026-10-02T17:00:00Z");worker.runOnce();worker.runOnce();
        assertThat(jobs("SENT")).isEqualTo(2);
        assertThat(reminders.overview(owner).reminders().stream().filter(r->!r.kind().equals("FOLLOW_UP")))
            .allSatisfy(r->{assertThat(r.state()).isEqualTo("READY");assertThat(r.emailStatus()).isEqualTo("SENT");});
        assertThat(reminders.overview(owner).reminders().stream().filter(r->r.kind().equals("FOLLOW_UP")))
            .allSatisfy(r->assertThat(r.state()).isEqualTo("PENDING"));
        at("2026-10-03T13:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(3);
    }

    @Test void sameDayEventsCreatedAfterDigestNotifyWithoutWaitingUntilTomorrow() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","09:00");
        at("2026-10-02T09:00:00Z");worker.runOnce();
        at("2026-10-02T10:00:00Z");expiringSources("2026-10-02");worker.runOnce();
        assertThat(jobs("SENT")).isEqualTo(2);
        assertThat(reminders.overview(owner).readyCount()).isEqualTo(2);
    }

    @Test void futureEventsDueAfterDigestStillWaitForNextDigest() {
        reminders.savePreferences(owner,"0",new Preferences(true,true,true,true,1,1,0,"UTC","13:00",DeliveryMode.DAILY_DIGEST,"09:00",true));
        expiringSources("2026-10-03");at("2026-10-02T09:00:00Z");worker.runOnce();
        at("2026-10-02T13:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isZero();
        at("2026-10-03T09:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);
        assertThat(reminders.overview(owner).readyCount()).isEqualTo(2);
    }

    @Test void sameDayFallbackMakesInAppRemindersReadyWithoutEmailOptIn() {
        reminders.savePreferences(owner,"0",new Preferences(true,true,true,true,0,0,0,"UTC","13:00",DeliveryMode.DAILY_DIGEST,"09:00",false));
        expiringSources("2026-10-02");at("2026-10-02T09:00:00Z");worker.runOnce();
        at("2026-10-02T13:00:00Z");worker.runOnce();
        assertThat(reminders.overview(owner).readyCount()).isEqualTo(2);
        assertThat(jobs("PENDING")).isZero();assertThat(jobs("SENT")).isZero();verifyNoInteractions(sender);
    }

    @Test void existingPreferencesOverviewDoesNotWaitForUserRowLock() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Future<?> locker=null;
        try {
            locker=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->{
                jdbc.queryForObject("SELECT id FROM campus_users WHERE id=? FOR UPDATE",UUID.class,owner);locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("User lock was not released"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var overview=executor.submit(()->reminders.overview(owner));
            assertThat(overview.get(1,TimeUnit.SECONDS).preferences().enabled()).isFalse();
        } finally {
            release.countDown();if (locker!=null) locker.get(5,TimeUnit.SECONDS);executor.shutdownNow();
        }
    }

    @Test void parallelFirstOverviewsCreateOnlyOnePreferencesRow() throws Exception {
        var first=users.saveAndFlush(new CampusUser("first-"+UUID.randomUUID()+"@example.test","Delivery Test","test-hash")).getId();
        var executor=Executors.newFixedThreadPool(4);
        try {
            var requests=new java.util.ArrayList<Future<Overview>>();
            for (int i=0;i<8;i++) requests.add(executor.submit(()->reminders.overview(first)));
            for (var request:requests) assertThat(request.get(10,TimeUnit.SECONDS).preferences().enabled()).isFalse();
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminder_preferences WHERE owner_id=?",Integer.class,first)).isEqualTo(1);
    }

    @Test void immediateDeliveryIsDeduplicatedAcrossTicks() {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");
        worker.runOnce();worker.runOnce();
        verify(sender,times(1)).send(contains("@example.test"),eq("Campus reminder"),contains("Delivery Labs"));
        assertThat(jobs("SENT")).isEqualTo(1);
        assertThat(reminders.overview(owner).reminders().get(0).emailStatus()).isEqualTo("SENT");
        disable();
    }
    @Test void dailyDigestUsesLocalTimeAndDefersLateArrivals() {
        enable(DeliveryMode.DAILY_DIGEST,"America/New_York","10:00");source("2026-10-02");
        at("2026-10-02T13:59:00Z");worker.runOnce();assertThat(reminders.overview(owner).readyCount()).isZero();verifyNoInteractions(sender);
        at("2026-10-02T14:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);
        source("2026-10-02");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);
        assertThat(reminders.overview(owner).reminders().stream().filter(r->r.state().equals("PENDING"))).hasSize(1);
        at("2026-10-03T14:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(2);
        verify(sender,times(2)).send(anyString(),eq("Campus daily reminder digest"),anyString());disable();
    }
    @Test void digestCombinesItemsAndDoesNotSendEmptyMessages() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","10:00");at("2026-10-02T09:30:00Z");worker.runOnce();verifyNoInteractions(sender);
        source("2026-10-02");source("2026-10-02");at("2026-10-02T10:00:00Z");worker.runOnce();
        assertThat(jobs("SENT")).isEqualTo(1);verify(sender,times(1)).send(anyString(),anyString(),contains("2 reminder(s)"));disable();
    }
    @Test void snoozedAndDeletedItemsAreRemovedFromQueuedDelivery() {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");var app=source("2026-10-02");at("2026-10-02T10:00:00Z");process();
        var item=reminders.overview(owner).reminders().get(0);reminders.snooze(owner,item.id(),""+item.version(),60);
        worker.runOnce();verifyNoInteractions(sender);
        at("2026-10-02T11:00:00Z");worker.runOnce();verify(sender,times(1)).send(anyString(),anyString(),anyString());
        var next=source("2026-10-02");process();jdbc.update("UPDATE applications SET deleted=TRUE WHERE id=?",next);reminders.reconcile(owner);
        worker.runOnce();verify(sender,times(1)).send(anyString(),anyString(),anyString());assertThat(jobs("CANCELLED")).isEqualTo(2);disable();
    }
    @Test void transientFailureRetriesAndRetainsInAppReminder() {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");
        doThrow(new IllegalStateException("synthetic SMTP outage")).doNothing().when(sender).send(anyString(),anyString(),anyString());
        worker.runOnce();assertThat(jobs("PENDING")).isEqualTo(1);assertThat(reminders.overview(owner).readyCount()).isEqualTo(1);
        at("2026-10-02T10:02:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);disable();
    }
    @Test void permanentAndExhaustedFailuresAreVisible() {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");
        doThrow(new PermanentDeliveryException()).when(sender).send(anyString(),anyString(),anyString());
        worker.runOnce();assertThat(jobs("FAILED")).isEqualTo(1);
        assertThat(reminders.overview(owner).reminders().get(0).emailStatus()).isEqualTo("FAILED");
        reset(sender);doThrow(new IllegalStateException()).when(sender).send(anyString(),anyString(),anyString());
        source("2026-10-02");process();jdbc.update("UPDATE reminder_deliveries SET attempts=5 WHERE owner_id=? AND status='PENDING'",owner);
        worker.runOnce();assertThat(jobs("FAILED")).isEqualTo(2);disable();
    }
    @Test void expiredSendingLeaseCanBeRecovered() {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");process();
        jdbc.update("UPDATE reminder_deliveries SET status='SENDING',attempts=1,lease_owner=?,lease_expires_at=? WHERE owner_id=?",UUID.randomUUID(),Timestamp.from(clock.instant().minusSeconds(1)),owner);
        worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);verify(sender,times(1)).send(anyString(),anyString(),anyString());disable();
    }
    @Test void unrelatedPreferenceEditDoesNotSendAnotherDigestOnTheSameDay() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");worker.runOnce();
        source("2026-10-02");var p=reminders.overview(owner).preferences();
        reminders.savePreferences(owner,""+p.version(),new Preferences(true,true,true,true,2,3,0,"UTC","09:00",DeliveryMode.DAILY_DIGEST,"09:00",true));
        worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);verify(sender,times(1)).send(anyString(),anyString(),anyString());disable();
    }
    @Test void parallelOverviewRequestsCreateOneOccurrence() throws Exception {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");
        var executor=Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<Future<Overview>>();
            for (int i=0;i<8;i++) futures.add(executor.submit(()->reminders.overview(owner)));
            for (var future:futures) assertThat(future.get(10,TimeUnit.SECONDS).reminders()).hasSize(1);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);disable();
    }
    @Test void emptyMorningDigestDefersItemsThatBecomeDueLater() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","09:00");
        var p=reminders.overview(owner).preferences();
        reminders.savePreferences(owner,""+p.version(),new Preferences(true,true,true,true,1,3,0,"UTC","13:00",DeliveryMode.DAILY_DIGEST,"09:00",true));
        source("2026-10-02");at("2026-10-02T09:00:00Z");worker.runOnce();verifyNoInteractions(sender);
        at("2026-10-02T13:00:00Z");worker.runOnce();verifyNoInteractions(sender);
        assertThat(reminders.overview(owner).readyCount()).isZero();
        at("2026-10-03T09:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);disable();
    }
    @Test void unrelatedPreferenceEditPreservesRetryingDigest() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","09:00");source("2026-10-02");at("2026-10-02T09:00:00Z");
        doThrow(new IllegalStateException()).doNothing().when(sender).send(anyString(),anyString(),anyString());
        worker.runOnce();assertThat(jobs("PENDING")).isEqualTo(1);
        var p=reminders.overview(owner).preferences();
        reminders.savePreferences(owner,""+p.version(),new Preferences(true,true,true,true,2,3,0,"UTC","09:00",DeliveryMode.DAILY_DIGEST,"09:00",true));
        at("2026-10-02T09:02:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);disable();
    }
    @Test void expiryIsRecheckedBeforeRetryOutsideOwnerPage() {
        // Set owner to the highest UUID so its current-source scan falls after the 100-owner page.
        owner=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,'test',?)",
            owner,"expiry-"+UUID.randomUUID()+"@example.test","Expiry Test",Timestamp.from(clock.instant()));
        reminders.overview(owner);enable(DeliveryMode.IMMEDIATE,"UTC","09:00");
        var now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,deadline,notes,created_at,updated_at) VALUES(?,?,'Expiry Labs','Intern','','SAVED',?,'',?,?)",
            UUID.randomUUID(),owner,LocalDate.parse("2026-10-02"),now,now);
        at("2026-10-02T10:00:00Z");reminders.processOwner(owner);
        for (int i=0;i<100;i++) {
            UUID id=UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d",i+1));
            jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,'test',?)",id,"page-"+UUID.randomUUID()+"@example.test","Page Test",now);
            jdbc.update("INSERT INTO reminder_preferences(owner_id,enabled) VALUES(?,TRUE)",id);
        }
        at("2026-10-03T00:01:00Z");
        // A fresh worker starts at page one even if the test singleton advanced a cursor earlier.
        new ReminderWorker(reminders,jdbc,manager,sender,config,clock).runOnce();verifyNoInteractions(sender);
        assertThat(jobs("CANCELLED")).isEqualTo(1);
        jdbc.update("UPDATE reminder_preferences SET enabled=FALSE WHERE owner_id IN (SELECT id FROM campus_users WHERE display_name='Page Test')");disable();
    }
    @Test void springForwardDigestWaitsForResolvedLocalTime() {
        at("2026-03-08T05:00:00Z");enable(DeliveryMode.DAILY_DIGEST,"America/New_York","02:30");
        var p=reminders.overview(owner).preferences();
        reminders.savePreferences(owner,""+p.version(),new Preferences(true,true,true,true,1,3,0,"America/New_York","02:30",DeliveryMode.DAILY_DIGEST,"02:30",true));
        source("2026-03-08");at("2026-03-08T07:00:00Z");worker.runOnce();verifyNoInteractions(sender);
        at("2026-03-08T07:30:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(1);disable();
    }
    @Test void digestCreatesMultipleBatchesWithoutLosingSameDayDeadlines() {
        enable(DeliveryMode.DAILY_DIGEST,"UTC","09:00");var now=Timestamp.from(clock.instant());
        for (int i=0;i<101;i++) jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,deadline,notes,created_at,updated_at) VALUES(?,?,'Batch Labs','Intern','','SAVED',?,'',?,?)",
            UUID.randomUUID(),owner,LocalDate.parse("2026-10-02"),now,now);
        at("2026-10-02T09:00:00Z");worker.runOnce();assertThat(jobs("SENT")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE owner_id=? AND email_sent_at IS NOT NULL",Integer.class,owner)).isEqualTo(101);disable();
    }
    @Test void independentWorkersCannotClaimTheSameMailJob() throws Exception {
        enable(DeliveryMode.IMMEDIATE,"UTC","09:00");source("2026-10-02");at("2026-10-02T10:00:00Z");process();
        var second=new ReminderWorker(reminders,jdbc,manager,sender,config,clock);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var firstRun=executor.submit(()->worker.runOnce());var secondRun=executor.submit(()->second.runOnce());
            firstRun.get(10,TimeUnit.SECONDS);secondRun.get(10,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        verify(sender,times(1)).send(anyString(),anyString(),anyString());assertThat(jobs("SENT")).isEqualTo(1);disable();
    }
    void disable() {
        var p=reminders.overview(owner).preferences();reminders.savePreferences(owner,""+p.version(),new Preferences(false,true,true,true,1,3,0,"UTC","09:00",DeliveryMode.IMMEDIATE,"09:00",false));
    }
}
