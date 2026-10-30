package com.campus.tracker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class CredentialVersionFilterTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    @Test void unavailablePrimaryFailsClosedWithoutLeakingDetailsOrExpiringCookie() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:missing-"+UUID.randomUUID(), "sa", "");
        var filter = new CredentialVersionFilter(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource), new ObjectMapper());
        var principal = new CampusPrincipal(UUID.randomUUID(), "Private", "private@example.test", "private-hash", true, 0L);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setServletPath("/api/auth/me");
        var session = new MockHttpSession(); request.setSession(session);
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();
        filter.doFilter(request, response, (req,res) -> dispatched.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(dispatched).isFalse();
        assertThat(response.getContentAsString()).doesNotContain("campus_users", "jdbc:", "private", "SELECT");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(session.isInvalid()).isFalse();
    }
    @Test void replacingCredentialsPreservesIdentityAppearanceAndVerification() {
        var user = new CampusUser("private@example.test", "Private", "old-hash");
        var id = user.getId();
        var verified = Instant.parse("2026-10-10T00:00:00Z"); user.verifyEmail(verified);
        user.replacePassword("new-hash", 42);
        assertThat(user.getCredentialVersion()).isEqualTo(1);
        assertThat(user.getResetRequestCutoff()).isEqualTo(42);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getEmail()).isEqualTo("private@example.test");
        assertThat(user.getDisplayName()).isEqualTo("Private");
        assertThat(user.getThemePreference()).isEqualTo("system");
        assertThat(user.getEmailVerifiedAt()).isEqualTo(verified);
        assertThat(user.isEmailVerificationExempt()).isFalse();
        var principal = CampusPrincipal.from(user);
        assertThat(principal.credentialVersion()).isEqualTo(1);
        assertThat(principal.toString()).doesNotContain("private", "Private", "new-hash", id.toString());
        assertThatThrownBy(() -> user.replacePassword("other-hash", 41)).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getCredentialVersion()).isEqualTo(1);
    }
}
