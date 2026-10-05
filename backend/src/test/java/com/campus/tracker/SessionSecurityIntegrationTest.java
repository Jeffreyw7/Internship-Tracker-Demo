package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @Test void authenticatedSessionRetainsIdentityWithoutPasswordHashOrCredentials() throws Exception {
        UUID id = UUID.randomUUID();
        String email = "session-" + id + "@example.test";
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at,email_verified_at) "
                + "VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, email, "Session test", passwords.encode("Campus-test-123!"));
        var login = mvc.perform(post("/api/auth/login").with(csrf()).param("email", email)
                .param("password", "Campus-test-123!")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        var security = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        var principal = (CampusPrincipal) security.getAuthentication().getPrincipal();
        assertThat(principal.id()).isEqualTo(id);
        assertThat(principal.passwordHash()).isNull();
        assertThat(security.getAuthentication().getCredentials()).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }
}
