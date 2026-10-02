package com.campus.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.time.*;
import java.util.concurrent.*;
import com.campus.tracker.auth.*;
import com.campus.tracker.mail.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"campus.password.mail-enabled=true",
        "campus.password.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "campus.password.rate-limit-secret=synthetic-test-secret-at-least-32-bytes",
        "campus.password.public-origin=http://127.0.0.1:8080",
        "campus.password.mail-from=campus@example.test", "campus.password.worker-enabled=false",
        "campus.password.limits.request-minute=100", "campus.password.limits.request-hour=1000",
        "campus.password.limits.confirm-minute=1000", "spring.datasource.url=jdbc:h2:mem:password-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordIntegrationTest.TestMail.class)
class PasswordIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxCrypto crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailOutboxWorker worker;
    @Autowired PlatformTransactionManager manager;
    @Autowired TestClock clock;
    @Autowired AuthRateLimiter limiter;
    @Autowired MailProperties mailConfig;
    @Autowired CoordinatedEncoder coordinatedEncoder;
    private static final String OLD="Campus-test-123!", NEW="Campus-new-456!";
    private static final List<EmailMessage> delivered=new CopyOnWriteArrayList<>();
    @TestConfiguration static class TestMail {
        @Bean @Primary EmailSender testSender(){return delivered::add;}
        @Bean @Primary TestClock testClock(){return new TestClock();}
        @Bean @Primary CoordinatedEncoder testEncoder(){return new CoordinatedEncoder();}
    }
    static class CoordinatedEncoder extends org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder {
        record Pause(CountDownLatch entered,CountDownLatch resume){}
        final java.util.concurrent.atomic.AtomicReference<Pause> pause=new java.util.concurrent.atomic.AtomicReference<>();
        CoordinatedEncoder(){super(12);}
        @Override public boolean matches(CharSequence raw,String hash){
            if(raw.toString().equals("password-request-timing-placeholder")){
                var gate=pause.getAndSet(null);
                if(gate!=null){gate.entered().countDown();try{if(!gate.resume().await(10,TimeUnit.SECONDS))throw new AssertionError("Issuance pause timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
            }
            return super.matches(raw,hash);
        }
    }
    static class TestClock extends Clock {
        volatile Instant value=Instant.parse("2026-10-02T12:00:00Z");
        @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public Clock withZone(java.time.ZoneId zone){return this;}
        @Override public Instant instant(){return value;}
    }
    @org.junit.jupiter.api.BeforeEach void resetClock(){clock.value=Instant.parse("2026-10-02T12:00:00Z");}
    CampusUser account(){return users.saveAndFlush(new CampusUser("password-"+UUID.randomUUID()+"@example.test","Password Test",encoder.encode(OLD)));}
    MockHttpSession login(String email,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("email",email).param("password",password))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    String token(CampusUser user,String purpose){
        return jdbc.query("SELECT o.id,o.payload,o.nonce,o.key_id FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=? AND c.purpose=? AND c.revoked_at IS NULL AND c.consumed_at IS NULL ORDER BY c.created_at DESC",
                (r,i)->crypto.decrypt(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4)).token(),user.getId(),purpose).get(0);
    }
    void confirm(String token,String password,int expected) throws Exception {
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token",token,"newPassword",password))))
                .andExpect(status().is(expected));
    }

    @Test void onlyIssuedEmailConsumesTheGlobalDeliveryBudget() {
        Instant previous=clock.value;
        int previousLimit=mailConfig.getMailDay();
        clock.value=Instant.parse("2035-01-01T12:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(mailConfig,"mailDay",2);
        try {
            int before=jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox",Integer.class);
            service.requestReset("unknown-"+UUID.randomUUID()+"@example.test");
            service.requestReset("unknown-"+UUID.randomUUID()+"@example.test");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox",Integer.class)).isEqualTo(before);
            CampusUser first=account(),second=account(),third=account();
            service.requestReset(first.getEmail());
            assertThat(token(first,"RESET")).hasSize(43);
            // Recipient-suppressed retries must not consume another global delivery slot.
            service.requestReset(first.getEmail());
            service.requestReset(second.getEmail());
            assertThat(token(second,"RESET")).hasSize(43);
            service.requestReset(third.getEmail());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox",Integer.class)).isEqualTo(before+2);
        } finally {
            clock.value=previous;
            org.springframework.test.util.ReflectionTestUtils.setField(mailConfig,"mailDay",previousLimit);
        }
    }

    @Test void unknownEmailIsAcceptedWithoutDisclosingExistence() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", "unknown@example.test"))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PASSWORD_RESET_REQUEST_ACCEPTED"));
    }
    @Test void passwordValidationErrorsAreNotCacheable() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"invalid\"}")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void resetRetainsCsrfProtection() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unknown@example.test\"}")).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void changeRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/password-change/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Campus-test-123!\"}")).andExpect(status().isUnauthorized());
    }
    @Test void invalidResetIsGeneric() throws Exception {
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", "a".repeat(43), "newPassword", "New-campus-123!"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_CHALLENGE_INVALID"));
    }
    @Test void recoveryChangesCredentialsAndRevokesTwoSessions() throws Exception {
        CampusUser user=account();MockHttpSession first=login(user.getEmail(),OLD),second=login(user.getEmail(),OLD);
        service.requestReset(user.getEmail());String token=token(user,"RESET");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_challenges WHERE user_id=?",String.class,user.getId())).isEqualTo(PasswordToken.hash(token));
        confirm(token,NEW,200);
        mvc.perform(get("/api/auth/me").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("email",user.getEmail()).param("password",OLD)).andExpect(status().isUnauthorized());
        login(user.getEmail(),NEW);confirm(token,NEW,400);
        assertThat(users.findById(user.getId()).orElseThrow().getCredentialVersion()).isEqualTo(1);
    }
    @Test void invalidPasswordDoesNotConsumeTheRecoveryToken() throws Exception {
        CampusUser user=account();service.requestReset(user.getEmail());String token=token(user,"RESET");
        confirm(token,"weak",400);confirm(token,NEW,200);
    }
    @Test void expiryIsExclusiveAndWrongPurposeIsRejected() throws Exception {
        CampusUser user=account();service.requestReset(user.getEmail());String token=token(user,"RESET");
        clock.value=clock.value.plusSeconds(1800);confirm(token,NEW,400);
        assertThatThrownBy(()->service.confirmChange(user.getId(),"binding",token,NEW)).isInstanceOf(com.campus.tracker.common.ApiException.class);
    }
    @Test void passwordChangeRequiresItsInitiatingSession() throws Exception {
        CampusUser user=account();MockHttpSession first=login(user.getEmail(),OLD),other=login(user.getEmail(),OLD);
        mvc.perform(post("/api/auth/password-change/request").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword",OLD)))).andExpect(status().isAccepted());
        String token=token(user,"CHANGE");
        String body=json.writeValueAsString(Map.of("token",token,"newPassword",NEW));
        mvc.perform(post("/api/auth/password-change/confirm").session(other).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password-change/confirm").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());login(user.getEmail(),NEW);
    }
    @Test void wrongCurrentPasswordAndForeignAccountCannotAuthorizeChange() throws Exception {
        CampusUser user=account(),foreign=account();var session=login(user.getEmail(),OLD);
        mvc.perform(post("/api/auth/password-change/request").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword","Wrong-password-123!"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        service.requestChange(user.getId(),OLD,"binding");String token=token(user,"CHANGE");
        assertThatThrownBy(()->service.confirmChange(foreign.getId(),"binding",token,NEW)).isInstanceOf(com.campus.tracker.common.ApiException.class);
    }
    @Test void concurrentConfirmationsHaveOneWinner() throws Exception {
        CampusUser user=account();service.requestReset(user.getEmail());String token=token(user,"RESET");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> attempt=()->{start.await();try{service.confirmReset(token,NEW);return true;}catch(com.campus.tracker.common.ApiException e){return false;}};
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally{pool.shutdownNow();}
    }
    @Test void encryptedOutboxDeliversAndErasesSecrets() {
        CampusUser user=account();service.requestReset(user.getEmail());String token=token(user,"RESET");
        var row=jdbc.queryForMap("SELECT o.* FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=?",user.getId());
        assertThat(row.get("payload").toString()).doesNotContain(token,user.getEmail());
        assertThatThrownBy(()->crypto.decrypt(UUID.randomUUID(),row.get("payload").toString(),row.get("nonce").toString(),row.get("key_id").toString())).isInstanceOf(IllegalStateException.class);
        worker.deliverPending();
        assertThat(delivered).anyMatch(m->m.recipient().equals(user.getEmail())&&m.token().equals(token));
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox WHERE id=?",String.class,row.get("id"))).isNull();
    }
    @Test void failedTransactionDoesNotReplacePasswordOrConsumeToken(){
        CampusUser user=account();service.requestReset(user.getEmail());String token=token(user,"RESET");
        var tx=new TransactionTemplate(manager);tx.executeWithoutResult(s->{service.confirmReset(token,NEW);s.setRollbackOnly();});
        assertThat(encoder.matches(OLD,users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        service.confirmReset(token,NEW);
    }
    @Test void anonymousResendPreservesOldLinksAndCompletionRevokesAll(){
        CampusUser user=account();service.requestReset(user.getEmail());String first=token(user,"RESET");
        clock.value=clock.value.plusSeconds(61);service.requestReset(user.getEmail());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_challenges WHERE user_id=? AND revoked_at IS NULL",Integer.class,user.getId())).isEqualTo(2);
        clock.value=clock.value.plusSeconds(61);service.requestChange(user.getId(),OLD,"binding");String change=token(user,"CHANGE");
        service.confirmReset(first,NEW);
        assertThatThrownBy(()->service.confirmChange(user.getId(),"binding",change,NEW)).isInstanceOf(com.campus.tracker.common.ApiException.class);
    }
    @Test void concurrentRateLimitBucketCreationAllowsExactlyOneEmail() throws Exception {
        String email="rate-"+UUID.randomUUID()+"@example.test";
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->limiter.reserveEmail(email)));
            int accepted=0;for(var result:futures)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally{pool.shutdownNow();}
    }
    @Test void rateCountersSurviveOuterTransactionRollback(){
        String email="rollback-"+UUID.randomUUID()+"@example.test";
        new TransactionTemplate(manager).executeWithoutResult(s->{assertThat(limiter.reserveEmail(email)).isTrue();s.setRollbackOnly();});
        assertThat(limiter.reserveEmail(email)).isFalse();
    }
    @Test void resetTargetsTheEmailedAccountEvenWhenAnotherAccountIsSignedIn() throws Exception {
        CampusUser target=account(),foreign=account();var session=login(foreign.getEmail(),OLD);
        service.requestReset(target.getEmail());String token=token(target,"RESET");
        mvc.perform(post("/api/auth/password-reset/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token",token,"newPassword",NEW)))).andExpect(status().isOk());
        assertThat(encoder.matches(OLD,users.findById(foreign.getId()).orElseThrow().getPasswordHash())).isTrue();
        login(target.getEmail(),NEW);login(foreign.getEmail(),OLD);
    }
    @Test void expiredLeaseRecoversTheSameCapabilityAndReplayStillFails(){
        CampusUser user=account();service.requestReset(user.getEmail());String secret=token(user,"RESET");
        UUID job=jdbc.queryForObject("SELECT o.id FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=?",UUID.class,user.getId());
        // Simulate process loss after SMTP acceptance but before the durable acknowledgement.
        jdbc.update("UPDATE email_outbox SET status='SENDING',attempts=1,lease_owner=?,lease_expires_at=? WHERE id=?",UUID.randomUUID(),java.sql.Timestamp.from(clock.instant().minusSeconds(1)),job);
        worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE id=?",String.class,job)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE id=?",Integer.class,job)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox WHERE id=?",String.class,job)).isNull();
        service.confirmReset(secret,NEW);
        assertThatThrownBy(()->service.confirmReset(secret,NEW)).isInstanceOf(com.campus.tracker.common.ApiException.class);
    }
    @Test void temporaryFailureRetriesAndExhaustionErasesNotification(){
        CampusUser user=account();service.requestReset(user.getEmail());service.confirmReset(token(user,"RESET"),NEW);
        UUID job=jdbc.queryForObject("SELECT id FROM email_outbox WHERE user_id=? AND message_type='CHANGED'",UUID.class,user.getId());
        var failing=new EmailOutboxWorker(jdbc,manager,crypto,m->{throw new IllegalStateException("Synthetic temporary failure");},mailConfig,clock);
        failing.deliverPending();
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE id=?",String.class,job)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox WHERE id=?",String.class,job)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM email_outbox WHERE id=?",java.sql.Timestamp.class,job).toInstant()).isAfter(clock.instant());
        jdbc.update("UPDATE email_outbox SET attempts=5,next_attempt_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),job);
        failing.deliverPending();
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE id=?",String.class,job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox WHERE id=?",String.class,job)).isNull();
    }
    @Test void expiredChallengesCancelQueuedMailAndErasePayload(){
        CampusUser user=account();service.requestReset(user.getEmail());
        clock.value=clock.instant().plus(mailConfig.getResetTtl());worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT o.status FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=?",String.class,user.getId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT o.payload FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=?",String.class,user.getId())).isNull();
    }
    @Test void simultaneousWorkersClaimEachJobOnlyOnce() throws Exception {
        CampusUser user=account();service.requestReset(user.getEmail());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sends=new java.util.concurrent.atomic.AtomicInteger();
        EmailSender sender=m->{if(m.recipient().equals(user.getEmail())){sends.incrementAndGet();entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Worker timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}};
        var first=new EmailOutboxWorker(jdbc,manager,crypto,sender,mailConfig,clock);
        var second=new EmailOutboxWorker(jdbc,manager,crypto,sender,mailConfig,clock);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(first::deliverPending);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            second.deliverPending();assertThat(sends.get()).isEqualTo(1);
            release.countDown();running.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void permanentMailFailureErasesPayloadRatherThanRetrying(){
        CampusUser user=account();service.requestReset(user.getEmail());
        var localWorker=new EmailOutboxWorker(jdbc,manager,crypto,m->{throw new PermanentDeliveryException();},
                mailConfig,clock);
        localWorker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT o.status FROM email_outbox o JOIN password_challenges c ON c.id=o.password_challenge_id WHERE c.user_id=?",String.class,user.getId())).isEqualTo("FAILED");
    }
    @Test void issuanceAfterConcurrentConfirmationUsesTheCurrentCredentialVersion() throws Exception {
        CampusUser user=account();service.requestReset(user.getEmail());String old=token(user,"RESET");
        clock.value=clock.value.plusSeconds(61);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        coordinatedEncoder.pause.set(new CoordinatedEncoder.Pause(entered,resume));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var issued=pool.submit(()->service.requestReset(user.getEmail()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            service.confirmReset(old,NEW);resume.countDown();issued.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT credential_version FROM password_challenges WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",Long.class,user.getId())).isEqualTo(1);
            String fresh=token(user,"RESET");worker.deliverPending();
            assertThat(delivered).anyMatch(m->m.recipient().equals(user.getEmail())&&fresh.equals(m.token()));
        } finally{resume.countDown();coordinatedEncoder.pause.set(null);pool.shutdownNow();}
    }
    static class PausingSession extends MockHttpSession {
        final CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
        @Override public void setAttribute(String name,Object value){
            if(name.equals("campus.password.change-binding")&&first.compareAndSet(true,false)){
                entered.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new AssertionError("Session pause timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }
            super.setAttribute(name,value);
        }
    }
    @Test void overlappingChangeRequestsCannotOverwriteTheNewestBinding() throws Exception {
        CampusUser user=account();var session=new PausingSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).param("email",user.getEmail()).param("password",OLD)).andExpect(status().isOk());
        var pool=Executors.newFixedThreadPool(2);
        Callable<Void> request=()->{mvc.perform(post("/api/auth/password-change/request").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword",OLD)))).andExpect(status().isAccepted());return null;};
        try {
            var first=pool.submit(request);assertThat(session.entered.await(5,TimeUnit.SECONDS)).isTrue();
            clock.value=clock.value.plusSeconds(61);var second=pool.submit(request);
            try{second.get(2,TimeUnit.SECONDS);}catch(TimeoutException expected){/* Serialized behind the first binding replacement. */}
            session.resume.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            String binding=(String)session.getAttribute("campus.password.change-binding");
            assertThat(jdbc.queryForObject("SELECT initiator_binding_hash FROM password_challenges WHERE user_id=? AND revoked_at IS NULL AND consumed_at IS NULL",String.class,user.getId())).isEqualTo(PasswordToken.hash(binding));
            service.confirmChange(user.getId(),binding,token(user,"CHANGE"),NEW);
        } finally{session.resume.countDown();pool.shutdownNow();}
    }
}
