package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:credential-session-race;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CredentialSessionRaceTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoSpyBean JdbcTemplate jdbc;
    @MockitoSpyBean PasswordEncoder passwords;

    @Test void unavailableLoginEpochCheckPreservesExistingOtherAccountsAuthentication() throws Exception {
        String password="Distinctive campus passphrase 847";
        var user=new CampusUser(UUID.randomUUID()+"@example.test","Attempted login",passwords.encode(password));
        user.verifyEmail(Instant.now());users.saveAndFlush(user);
        var existingUser=new CampusUser(UUID.randomUUID()+"@example.test","Existing login",user.getPasswordHash());
        existingUser.verifyEmail(Instant.now());users.saveAndFlush(existingUser);
        var principal=CampusPrincipal.from(existingUser);
        var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        var existingContext=new SecurityContextImpl(authentication);
        var session=new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",existingContext);
        doThrow(new DataAccessResourceFailureException("Synthetic login epoch outage"))
                .when(jdbc).queryForList(eq("SELECT credential_version FROM campus_users WHERE id=?"),eq(Long.class),eq(user.getId()));
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .param("email",user.getEmail()).param("password",password)).andExpect(status().isServiceUnavailable());
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(existingContext);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingUser.getId().toString()));
    }

    @Test void loginRevokedDuringPasswordCheckDoesNotOverwriteAnotherAccountsNewerLogin() throws Exception {
        String oldPassword="Older distinctive campus passphrase 847";
        String nextPassword="Newer distinctive campus passphrase 936";
        var user=new CampusUser(UUID.randomUUID()+"@example.test","Older login",passwords.encode(oldPassword));
        user.verifyEmail(Instant.now());users.saveAndFlush(user);
        var nextUser=new CampusUser(UUID.randomUUID()+"@example.test","Newer login",passwords.encode(nextPassword));
        nextUser.verifyEmail(Instant.now());users.saveAndFlush(nextUser);
        var session=new MockHttpSession();
        var passwordChecked=new CountDownLatch(1);
        var releaseOldLogin=new CountDownLatch(1);
        doAnswer(invocation->{
            Object matched=invocation.callRealMethod();
            passwordChecked.countDown();
            assertThat(releaseOldLogin.await(15,TimeUnit.SECONDS)).isTrue();
            return matched;
        }).when(passwords).matches(eq(oldPassword),eq(user.getPasswordHash()));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var oldLogin=pool.submit(()->mvc.perform(post("/api/auth/login").session(session).with(csrf())
                    .param("email",user.getEmail()).param("password",oldPassword)).andReturn());
            assertThat(passwordChecked.await(15,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE campus_users SET credential_version=credential_version+1 WHERE id=?",user.getId());
            mvc.perform(post("/api/auth/login").session(session).with(csrf())
                    .param("email",nextUser.getEmail()).param("password",nextPassword)).andExpect(status().isOk());
            releaseOldLogin.countDown();
            assertThat(oldLogin.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
            assertThat(session.isInvalid()).isFalse();
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(nextUser.getId().toString()));
        } finally { releaseOldLogin.countDown();pool.shutdownNow(); }
    }

    @Test void delayedRejectedLoginDoesNotInvalidateAnotherAccountsNewerLogin() throws Exception {
        String password="Distinctive campus passphrase 847";
        String nextPassword="Newer distinctive campus passphrase 936";
        var user=new CampusUser(UUID.randomUUID()+"@example.test","Older login",passwords.encode(password));
        user.verifyEmail(Instant.now());users.saveAndFlush(user);
        var nextUser=new CampusUser(UUID.randomUUID()+"@example.test","Newer login",passwords.encode(nextPassword));
        nextUser.verifyEmail(Instant.now());users.saveAndFlush(nextUser);
        var session=new MockHttpSession();
        var oldCheckStarted=new CountDownLatch(1);
        var releaseOldCheck=new CountDownLatch(1);
        var nextPasswordChecked=new CountDownLatch(1);
        var firstCheck=new AtomicBoolean(true);
        doAnswer(invocation->{
            if (firstCheck.compareAndSet(true,false)) {
                oldCheckStarted.countDown();
                assertThat(releaseOldCheck.await(15,TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForList(eq("SELECT credential_version FROM campus_users WHERE id=?"),eq(Long.class),eq(user.getId()));
        doAnswer(invocation->{
            Object matched=invocation.callRealMethod();
            nextPasswordChecked.countDown();
            return matched;
        }).when(passwords).matches(eq(nextPassword),eq(nextUser.getPasswordHash()));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var oldLogin=pool.submit(()->mvc.perform(post("/api/auth/login").session(session).with(csrf())
                    .param("email",user.getEmail()).param("password",password)).andReturn());
            assertThat(oldCheckStarted.await(15,TimeUnit.SECONDS)).isTrue();
            var nextLogin=pool.submit(()->mvc.perform(post("/api/auth/login").session(session).with(csrf())
                    .param("email",nextUser.getEmail()).param("password",nextPassword)).andReturn());
            assertThat(nextPasswordChecked.await(15,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE campus_users SET credential_version=credential_version+1 WHERE id=?",user.getId());
            releaseOldCheck.countDown();
            assertThat(oldLogin.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
            assertThat(nextLogin.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(session.isInvalid()).isFalse();
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(nextUser.getId().toString()));
        } finally { releaseOldCheck.countDown();pool.shutdownNow(); }
    }

    @Test void delayedOldEpochRejectionDoesNotInvalidateNewLoginUsingRotatedSessionId() throws Exception {
        String password="Distinctive campus passphrase 847";
        var user=new CampusUser(UUID.randomUUID()+"@example.test","Session race",passwords.encode(password));
        user.verifyEmail(Instant.now());users.saveAndFlush(user);
        var nextUser=new CampusUser(UUID.randomUUID()+"@example.test","New session owner",user.getPasswordHash());
        nextUser.verifyEmail(Instant.now());users.saveAndFlush(nextUser);
        var principal=CampusPrincipal.from(user);
        var session=new MockHttpSession();
        var auth=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        session.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(auth));
        String oldSessionId=session.getId();
        var oldCheckStarted=new CountDownLatch(1);
        var releaseOldCheck=new CountDownLatch(1);
        var loginReachedPasswordCheck=new CountDownLatch(1);
        var releaseLogin=new CountDownLatch(1);
        var firstCheck=new AtomicBoolean(true);
        doAnswer(invocation->{
            if (firstCheck.compareAndSet(true,false)) {
                oldCheckStarted.countDown();
                assertThat(releaseOldCheck.await(15,TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForList(eq("SELECT credential_version FROM campus_users WHERE id=?"),eq(Long.class),eq(user.getId()));
        doAnswer(invocation->{
            loginReachedPasswordCheck.countDown();
            assertThat(releaseLogin.await(15,TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(passwords).matches(eq(password),eq(user.getPasswordHash()));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var oldRequest=pool.submit(()->mvc.perform(get("/api/auth/me").session(session)).andReturn());
            assertThat(oldCheckStarted.await(15,TimeUnit.SECONDS)).isTrue();
            var login=pool.submit(()->mvc.perform(post("/api/auth/login").session(session).with(csrf())
                    .param("email",nextUser.getEmail()).param("password",password)).andReturn());
            assertThat(loginReachedPasswordCheck.await(15,TimeUnit.SECONDS)).isTrue();
            // The login passed the old session fence before another device revoked that account.
            jdbc.update("UPDATE campus_users SET credential_version=credential_version+1 WHERE id=?",user.getId());
            releaseLogin.countDown();
            assertThat(login.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(session.getId()).isNotEqualTo(oldSessionId);
            releaseOldCheck.countDown();
            assertThat(oldRequest.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
            assertThat(session.isInvalid()).isFalse();
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(nextUser.getId().toString()));
        } finally {
            releaseLogin.countDown();releaseOldCheck.countDown();pool.shutdownNow();
        }
    }
}
