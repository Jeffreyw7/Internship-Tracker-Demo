package com.campus.tracker;

import com.campus.tracker.auth.*;
import com.campus.tracker.auth.reset.*;
import com.campus.tracker.mail.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reset-post-commit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PasswordResetPostCommitTest {
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PasswordResetService resets;
    @Autowired PasswordResetRequestWorker worker;
    @Autowired PasswordResetController controller;
    @Autowired OutboxCrypto crypto;
    @MockitoSpyBean JdbcTemplate jdbc;

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void committedResetSucceedsAndInvalidatesItsSessionWhenPrimaryFailsAfterCommit() {
        exerciseCommittedReset(true);
    }
    @Test void committedResetPreservesAnotherAccountsSessionWhenPrimaryFailsAfterCommit() {
        exerciseCommittedReset(false);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exerciseCommittedReset(boolean sameAccount) {
        jdbc.update("UPDATE password_reset_control SET enabled=true,operator_intake_paused=false,health_intake_paused=false WHERE id=1");
        CampusUser owner=account();
        CampusUser caller=sameAccount?owner:account();
        resets.request(owner.getEmail(),"192.0.2.232"); worker.processPending();
        Map<String,Object> job=jdbc.queryForMap("SELECT o.id,o.payload,o.nonce,o.key_id,o.password_reset_token_id FROM email_outbox o "
                +"JOIN password_reset_tokens t ON t.id=o.password_reset_token_id WHERE t.email=? AND o.message_type='PASSWORD_RESET_LINK'",owner.getEmail());
        String token=crypto.decrypt((UUID)job.get("id"),EmailMessage.Type.PASSWORD_RESET_LINK,
                (String)job.get("payload"),(String)job.get("nonce"),(String)job.get("key_id")).token();
        var committed=new AtomicBoolean();
        // Arm the outage only after the real completion transaction has durably committed.
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed.set(true); }
            });
            return result;
        }).when(jdbc).update(startsWith("INSERT INTO auth_security_events("),any(Object[].class));
        doAnswer(invocation -> {
            if (committed.get()) throw new DataAccessResourceFailureException("Synthetic post-commit primary outage");
            return invocation.callRealMethod();
        }).when(jdbc).query(eq("SELECT credential_version FROM campus_users WHERE id=?"),any(ResultSetExtractor.class),any(Object[].class));
        var principal=CampusPrincipal.from(caller);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        var session=new MockHttpSession();
        var request=new MockHttpServletRequest("POST","/api/auth/password-reset/confirm");
        request.setSession(session); request.setRemoteAddr("192.0.2.233");
        var response=controller.confirm(new PasswordResetController.Confirmation(token,"A fresh recovery passphrase 928"),request);
        assertThat(committed).isTrue();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new AuthDtos.Accepted("PASSWORD_RESET_COMPLETE","Your password has been reset. Sign in with your new password."));
        assertThat(response.getBody().toString()).doesNotContain(owner.getId().toString(),owner.getEmail(),token);
        assertThat(session.isInvalid()).isEqualTo(sameAccount);
        if (sameAccount) assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        else assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
        CampusUser changed=users.findById(owner.getId()).orElseThrow();
        assertThat(changed.getCredentialVersion()).isEqualTo(1);
        assertThat(passwords.matches("A fresh recovery passphrase 928",changed.getPasswordHash())).isTrue();
        Object challenge=job.get("password_reset_token_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE id=? AND consumed_at IS NOT NULL",Long.class,challenge)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,challenge)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE password_reset_token_id=? AND message_type='PASSWORD_CHANGED'",Long.class,challenge)).isEqualTo(1);
    }
    private CampusUser account() {
        var user=new CampusUser(UUID.randomUUID()+"@example.test","Post-commit test",passwords.encode("Existing campus passphrase 479"));
        user.verifyEmail(Instant.now());return users.saveAndFlush(user);
    }
}
