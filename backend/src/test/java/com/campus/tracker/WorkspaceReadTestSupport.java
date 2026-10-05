package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class WorkspaceReadTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    UUID owner;
    @BeforeEach void account() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", owner, owner + "@example.test", "Read Test", "synthetic", Instant.now().atOffset(ZoneOffset.UTC));
    }
    MockHttpServletRequestBuilder request() {
        return get("/api/v2/applications").with(user(new CampusPrincipal(owner, "Read Test", owner + "@example.test", null, true)));
    }
    JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    UUID insert(String company, String status, LocalDate applied, LocalDate deadline, LocalDate follow) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,notes,applied_date,deadline,follow_up_date,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, owner, company, "Intern", "Remote", status, "private notes", applied, deadline, follow, OffsetDateTime.parse("2026-10-01T12:00:00Z"), OffsetDateTime.parse("2026-10-02T12:00:00Z"));
        return id;
    }
}
