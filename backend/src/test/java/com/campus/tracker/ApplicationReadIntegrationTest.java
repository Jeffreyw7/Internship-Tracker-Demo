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
class ApplicationReadIntegrationTest extends WorkspaceReadTestSupport {
    @Test void pageHasEightProjectionsStableTiesAndOwnedCursor() throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 9; i++) ids.add(insert("Company", "APPLIED", null, null, null));
        var deleted = insert("Deleted", "APPLIED", null, null, null);
        jdbc.update("UPDATE applications SET deleted=true WHERE id=?", deleted);
        var first = page(request());
        assertThat(first.get("content")).hasSize(8);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(first.get("readAt").asText()).isNotBlank();
        for (var item : first.get("content")) {
            assertThat(item.has("notes")).isFalse(); assertThat(item.has("stages")).isFalse();
            assertThat(item.get("nextActivity").isNull()).isTrue();
        }
        var last = page(request().param("cursor", first.get("nextCursor").asText()));
        assertThat(last.get("content")).hasSize(1);
        assertThat(last.get("hasNext").asBoolean()).isFalse();
        var visited = new HashSet<String>();
        first.get("content").forEach(item -> visited.add(item.get("id").asText()));
        last.get("content").forEach(item -> visited.add(item.get("id").asText()));
        assertThat(visited).containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        var oldCursor = first.get("nextCursor").asText();
        account(); insert("Other owner", "APPLIED", null, null, null);
        mvc.perform(request().param("cursor", oldCursor)).andExpect(status().isBadRequest());
        assertThat(page(request()).get("content")).hasSize(1);
    }
    @Test void searchIncludesStagesAndTreatsWildcardCharactersLiterally() throws Exception {
        var id = insert("100%_\\ Labs", "APPLIED", null, null, null);
        insert("Another Company", "APPLIED", null, null, null);
        jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,state) VALUES(?,?,0,?,'PLANNED')", UUID.randomUUID(), id, "Distributed Systems");
        assertThat(page(request().param("q", "systems")).get("content")).hasSize(1);
        assertThat(page(request().param("q", "%_\\")).get("content")).hasSize(1);
        assertThat(page(request().param("q", "private notes")).get("content")).hasSize(2);
    }
    @Test void tabsStatusAndDueUseClientCalendarDateAndPreserveOffer() throws Exception {
        var today = LocalDate.parse("2026-11-01");
        for (String status : List.of("SAVED", "APPLIED", "INTERVIEWING", "OFFER", "REJECTED", "WITHDRAWN")) insert(status, status, null, null, today);
        assertThat(page(request().param("tab", "active")).get("content")).hasSize(2);
        assertThat(page(request().param("tab", "saved")).get("content")).hasSize(1);
        assertThat(page(request().param("tab", "closed")).get("content")).hasSize(3);
        assertThat(page(request().param("status", "Offer")).get("content")).hasSize(1);
        assertThat(page(request().param("dueOnly", "true").param("today", "2026-10-31")).get("content")).isEmpty();
        assertThat(page(request().param("dueOnly", "true").param("today", today.toString())).get("content")).hasSize(4);
    }
    @Test void dateNullsAreLastAndCompanyUsesLowercaseDatabaseOrdering() throws Exception {
        insert("zeta", "APPLIED", null, null, null);
        insert("Beta", "APPLIED", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-01"), null);
        insert("alpha", "APPLIED", LocalDate.parse("2026-02-01"), LocalDate.parse("2026-11-01"), null);
        for (String sort : List.of("company", "deadline", "applied")) {
            var result = new ArrayList<String>(); String cursor = null;
            do {
                var query = request().param("sort", sort).param("size", "1");
                if (cursor != null) query.param("cursor", cursor);
                var response = page(query); result.add(response.get("content").get(0).get("company").asText());
                cursor = response.get("nextCursor").isNull() ? null : response.get("nextCursor").asText();
            } while (cursor != null);
            assertThat(result).containsExactly("alpha", "Beta", "zeta");
        }
    }
    @Test void nextActivityIsBoundedAndIncludesOfferStagesAndSavedDeadlines() throws Exception {
        var offer = insert("Offer", "OFFER", null, null, LocalDate.parse("2026-11-03"));
        var stage = UUID.randomUUID();
        jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,0,?,?,'SCHEDULED')", stage, offer, "Meet team", LocalDate.parse("2026-11-02"));
        insert("Saved", "SAVED", null, LocalDate.parse("2026-11-01"), null);
        insert("Rejected", "REJECTED", null, null, LocalDate.parse("2026-10-01"));
        var response = page(request().param("sort", "company"));
        assertThat(response.get("content").get(0).get("nextActivity").get("stageId").asText()).isEqualTo(stage.toString());
        assertThat(response.get("content").get(1).get("nextActivity").isNull()).isTrue();
        assertThat(response.get("content").get(2).get("nextActivity").get("kind").asText()).isEqualTo("Deadline");
    }
    @Test void rejectsBadBoundsFiltersDatesAndCursors() throws Exception {
        for (String cursor : List.of("garbage", "x".repeat(2049))) mvc.perform(request().param("cursor", cursor)).andExpect(status().isBadRequest());
        for (String size : List.of("0", "101")) mvc.perform(request().param("size", size)).andExpect(status().isBadRequest());
        mvc.perform(request().param("tab", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(request().param("sort", "notes")).andExpect(status().isBadRequest());
        mvc.perform(request().param("status", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(request().param("today", "2026-02-30")).andExpect(status().isBadRequest());
        mvc.perform(request().param("q", "x".repeat(201))).andExpect(status().isBadRequest());
        insert("A", "APPLIED", null, null, null); insert("B", "APPLIED", null, null, null);
        var cursor = page(request().param("size", "1")).get("nextCursor").asText();
        mvc.perform(request().param("size", "1").param("q", "A").param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/applications")).andExpect(status().isUnauthorized());
    }
}
