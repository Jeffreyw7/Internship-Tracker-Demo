package com.campus.tracker;

import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.auth.reset.PasswordResetRequestWorker;
import com.campus.tracker.auth.reset.ResetMaintenance;
import com.campus.tracker.auth.reset.ResetHealth;
import com.campus.tracker.auth.reset.DatabaseTime;
import com.campus.tracker.auth.reset.RecoveryCapacity;
import com.campus.tracker.mail.OutboxCrypto;
import com.campus.tracker.mail.EmailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:password-reset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PasswordResetRequestWorker worker;
    @MockitoSpyBean OutboxCrypto crypto;
    @Autowired ResetMaintenance maintenance;
    @Autowired ResetHealth health;
    @Autowired DatabaseTime databaseTime;
    @Autowired RecoveryCapacity capacity;

    @BeforeEach void enableAndCleanRecovery() {
        jdbc.update("UPDATE password_reset_control SET enabled=TRUE,operator_intake_paused=FALSE,health_intake_paused=FALSE WHERE id=1");
        jdbc.update("DELETE FROM email_outbox WHERE message_type<>'EMAIL_VERIFICATION'");
        jdbc.update("DELETE FROM password_reset_tokens");
        jdbc.update("DELETE FROM password_reset_requests");
        jdbc.update("DELETE FROM auth_rate_limits");
    }

    @Test void anonymousRequestIsAcceptedPrivatelyWithoutIssuingSynchronously() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String response = mvc.perform(post("/api/auth/password-reset/request").with(csrf())
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PASSWORD_RESET_REQUEST_ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(email, "token", "userId", "requestId");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests WHERE status='PENDING'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Integer.class)).isZero();
    }

    @Test void missingCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").contentType("application/json")
                .content("{\"email\":\"student@example.test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void signupAcceptsLongPassphraseWithoutCompositionRules() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.test",
                        "displayName","Policy test","password","four unusual words together","includeSamples",false))))
                .andExpect(status().isAccepted());
    }

    @Test void successfulResetPreservesAccountAndRejectsReuseAndOldPassword() throws Exception {
        CampusUser user=account(true);
        String previous=user.getPasswordHash();
        String token=issuedToken(user.getEmail());
        confirm(token,"unusual new passphrase chosen",200);
        CampusUser updated=users.findById(user.getId()).orElseThrow();
        assertThat(updated.getPasswordHash()).isNotEqualTo(previous);
        assertThat(passwords.matches("unusual new passphrase chosen",updated.getPasswordHash())).isTrue();
        assertThat(updated.getEmailVerifiedAt()).isEqualTo(user.getEmailVerifiedAt());
        assertThat(updated.getThemePreference()).isEqualTo(user.getThemePreference());
        assertThat(updated.getCredentialVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id IN (SELECT id FROM password_reset_tokens WHERE user_id=?)",Integer.class,user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE message_type='PASSWORD_CHANGED' AND password_reset_token_id IN (SELECT id FROM password_reset_tokens WHERE user_id=?)",Integer.class,user.getId())).isEqualTo(1);
        confirm(token,"another unusual passphrase",400);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email",user.getEmail()).param("password","old authentic passphrase"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("email",user.getEmail()).param("password","unusual new passphrase chosen"))
                .andExpect(status().isOk());
    }

    @Test void pendingAccountResetDoesNotBypassVerification() throws Exception {
        CampusUser user=account(false);
        confirm(issuedToken(user.getEmail()),"new pending account passphrase",200);
        CampusUser updated=users.findById(user.getId()).orElseThrow();
        assertThat(updated.canAuthenticate()).isFalse();
        mvc.perform(post("/api/auth/login").with(csrf()).param("email",user.getEmail()).param("password","new pending account passphrase"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_REQUIRED"));
    }

    @Test void strictContractRejectsUnknownDuplicateFieldsAndOversizedBodies() throws Exception {
        for (String body:List.of("{\"email\":\"one@example.test\",\"userId\":\"forged\"}",
                "{\"email\":\"one@example.test\",\"email\":\"two@example.test\"}"))
            mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json").content(" ".repeat(4097)))
                .andExpect(status().isPayloadTooLarge());
        for (String token:List.of("short","A".repeat(42)+"B","A".repeat(43)))
            mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",token,"newPassword","unusual new passphrase chosen"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID"));
    }

    @Test void exactExpiryAndQueuedOlderIntakeCannotReviveCredentials() throws Exception {
        CampusUser user=account(true);
        String expired=issuedToken(user.getEmail());
        jdbc.update("UPDATE password_reset_tokens SET expires_at=CURRENT_TIMESTAMP WHERE user_id=?",user.getId());
        confirm(expired,"unusual new passphrase chosen",400);
        jdbc.update("DELETE FROM auth_rate_limits");
        String live=issuedToken(user.getEmail());
        jdbc.update("DELETE FROM auth_rate_limits");
        requestEmail(user.getEmail()); // leave queued while completing an earlier issued link
        confirm(live,"unusual new passphrase chosen",200);
        worker.processPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id=? AND credential_version=1",Integer.class,user.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests WHERE payload IS NOT NULL",Integer.class)).isZero();
    }

    @Test void disabledAndPausedControlsFailClosedButPauseAllowsConfirmation() throws Exception {
        CampusUser user=account(true);
        String token=issuedToken(user.getEmail());
        jdbc.update("UPDATE password_reset_control SET operator_intake_paused=TRUE WHERE id=1");
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",user.getEmail())))).andExpect(status().isServiceUnavailable());
        confirm(token,"unusual new passphrase chosen",200);
        jdbc.update("UPDATE password_reset_control SET enabled=FALSE WHERE id=1");
        confirm(token,"another unusual passphrase chosen",503);
    }

    @Test void concurrentConfirmationsCompleteOnlyOnce() throws Exception {
        CampusUser user=account(true);
        String token=issuedToken(user.getEmail());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> action=()->{start.await();return mvc.perform(post("/api/auth/password-reset/confirm").with(csrf())
                .contentType("application/json").content(json.writeValueAsString(Map.of("token",token,"newPassword","unusual new passphrase chosen"))))
                .andReturn().getResponse().getStatus();};
        try {
            var a=pool.submit(action); var b=pool.submit(action); start.countDown();
            assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
        } finally { pool.shutdownNow(); }
        assertThat(users.findById(user.getId()).orElseThrow().getCredentialVersion()).isEqualTo(1);
    }

    @Test void auditFailureRollsBackCredentialsConsumptionCutoffAndNotice() throws Exception {
        CampusUser user=account(true);String token=issuedToken(user.getEmail());
        UUID id=jdbc.queryForObject("SELECT id FROM password_reset_tokens WHERE user_id=?",UUID.class,user.getId());
        jdbc.execute("ALTER TABLE auth_security_events ADD CONSTRAINT test_audit_failure CHECK (completion_id <> '"+id+"')");
        try {
            confirm(token,"unusual new passphrase chosen",503);
            CampusUser unchanged=users.findById(user.getId()).orElseThrow();
            assertThat(unchanged.getCredentialVersion()).isZero();assertThat(unchanged.getResetRequestCutoff()).isZero();
            assertThat(passwords.matches("old authentic passphrase",unchanged.getPasswordHash())).isTrue();
            assertThat(jdbc.queryForObject("SELECT consumed_at FROM password_reset_tokens WHERE id=?",java.sql.Timestamp.class,id)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE message_type='PASSWORD_CHANGED' AND password_reset_token_id=?",Integer.class,id)).isZero();
        } finally { jdbc.execute("ALTER TABLE auth_security_events DROP CONSTRAINT test_audit_failure"); }
        confirm(token,"unusual new passphrase chosen",200);
    }

    @Test void maintenanceErasesExpiredIntakeWithoutMailOrAccountCreation() throws Exception {
        requestEmail(UUID.randomUUID()+"@example.test");
        jdbc.update("UPDATE password_reset_requests SET created_at=DATEADD('MINUTE',-20,CURRENT_TIMESTAMP),expires_at=DATEADD('MINUTE',-10,CURRENT_TIMESTAMP)");
        maintenance.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests WHERE status='CANCELLED' AND payload IS NULL AND key_id IS NULL AND nonce IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens",Integer.class)).isZero();
    }

    @Test void knownUnknownAndRecipientSuppressedResponsesAreIdentical() throws Exception {
        CampusUser user=account(true);
        String known=requestBody(user.getEmail());String unknown=requestBody(UUID.randomUUID()+"@example.test");String suppressed=requestBody(user.getEmail());
        assertThat(known).isEqualTo(unknown).isEqualTo(suppressed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests",Integer.class)).isEqualTo(2);
    }

    @Test void intakeDoesNotReadAccountsAndEncryptionFailureNeverAcknowledges() throws Exception {
        CampusUser user=account(true);clearInvocations(users);
        requestEmail(user.getEmail());verifyNoInteractions(users);
        doThrow(new IllegalStateException("synthetic encryption failure")).when(crypto).encryptIntake(any(UUID.class),anyString());
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.test")))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests",Integer.class)).isEqualTo(1);
    }

    @Test void maintenanceReportsMissedChangedNoticeAsFailureAndErasesSecrets() throws Exception {
        CampusUser user=account(true);confirm(issuedToken(user.getEmail()),"unusual new passphrase chosen",200);
        jdbc.update("UPDATE email_outbox SET created_at=DATEADD('HOUR',-25,CURRENT_TIMESTAMP) WHERE message_type='PASSWORD_CHANGED'");
        maintenance.run();
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE message_type='PASSWORD_CHANGED'",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox WHERE message_type='PASSWORD_CHANGED'",String.class)).isNull();
    }
    @Test void workerHealthPauseRejectsNewIntakeButKeepsDeliveredConfirmationAvailable() throws Exception {
        CampusUser user=account(true);String token=issuedToken(user.getEmail());
        health.check(); // test profile intentionally disables automatic workers
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.test")))).andExpect(status().isServiceUnavailable());
        confirm(token,"unusual new passphrase chosen",200);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","SENDING"})
    void overdueIssuedLinkPausesIntakeUntilMailDrains(String mailStatus) throws Exception {
        issuedToken(account(true).getEmail());
        var enabledHealth=enabledWorkerHealth();
        enabledHealth.check();
        assertThat(intakeHealthPaused()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests WHERE status='DONE'",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE email_outbox SET status=?,created_at=DATEADD('MINUTE',-3,CURRENT_TIMESTAMP) WHERE message_type='PASSWORD_RESET_LINK'",mailStatus);
        if (mailStatus.equals("SENDING")) jdbc.update("UPDATE email_outbox SET lease_owner=?,lease_expires_at=DATEADD('MINUTE',2,CURRENT_TIMESTAMP) WHERE message_type='PASSWORD_RESET_LINK'",UUID.randomUUID());
        enabledHealth.check();
        assertThat(intakeHealthPaused()).isTrue();
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@example.test")))).andExpect(status().isServiceUnavailable());
        jdbc.update("UPDATE email_outbox SET status='SENT',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL WHERE message_type='PASSWORD_RESET_LINK'");
        enabledHealth.check();
        assertThat(intakeHealthPaused()).isFalse();
        requestEmail(UUID.randomUUID()+"@example.test");
    }

    @ParameterizedTest @ValueSource(strings={
            "expires_at=CURRENT_TIMESTAMP",
            "consumed_at=CURRENT_TIMESTAMP",
            "revoked_at=CURRENT_TIMESTAMP",
            "credential_version=credential_version+1",
            "email='stale@example.test'"})
    void ineligibleOldLinksDoNotPauseIntake(String tokenMutation) throws Exception {
        issuedToken(account(true).getEmail());
        jdbc.update("UPDATE email_outbox SET created_at=DATEADD('MINUTE',-3,CURRENT_TIMESTAMP) WHERE message_type='PASSWORD_RESET_LINK'");
        jdbc.update("UPDATE password_reset_tokens SET "+tokenMutation);
        enabledWorkerHealth().check();
        assertThat(intakeHealthPaused()).isFalse();
    }

    @Test void intentionallyDeferredChangedNoticeDoesNotPauseIntake() throws Exception {
        confirm(issuedToken(account(true).getEmail()),"unusual new passphrase chosen",200);
        jdbc.update("UPDATE email_outbox SET created_at=DATEADD('MINUTE',-3,CURRENT_TIMESTAMP),next_attempt_at=DATEADD('HOUR',1,CURRENT_TIMESTAMP),failure_category='NOTICE_BUDGET' WHERE message_type='PASSWORD_CHANGED'");
        enabledWorkerHealth().check();
        assertThat(intakeHealthPaused()).isFalse();
    }

    private ResetHealth enabledWorkerHealth() {
        return new ResetHealth(jdbc,databaseTime,worker,maintenance,capacity,true);
    }
    private boolean intakeHealthPaused() {
        return jdbc.queryForObject("SELECT health_intake_paused FROM password_reset_control WHERE id=1",Boolean.class);
    }

    private String requestBody(String email) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",email)))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private CampusUser account(boolean verified) {
        CampusUser user=new CampusUser(UUID.randomUUID()+"@example.test","Recovery test",passwords.encode("old authentic passphrase"));
        if (verified) user.verifyEmail(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return users.saveAndFlush(user);
    }
    private void requestEmail(String email) throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",email)))).andExpect(status().isAccepted());
    }
    private String issuedToken(String email) throws Exception {
        requestEmail(email); worker.processPending();
        Map<String,Object> row=jdbc.queryForMap("SELECT o.id,o.payload,o.nonce,o.key_id FROM email_outbox o JOIN password_reset_tokens t ON t.id=o.password_reset_token_id WHERE t.email=? AND o.message_type='PASSWORD_RESET_LINK' ORDER BY t.created_at DESC LIMIT 1",email);
        return crypto.decrypt((UUID)row.get("id"),EmailMessage.Type.PASSWORD_RESET_LINK,(String)row.get("payload"),(String)row.get("nonce"),(String)row.get("key_id")).token();
    }
    private void confirm(String token,String password,int expected) throws Exception {
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",token,"newPassword",password)))).andExpect(status().is(expected));
    }
}
