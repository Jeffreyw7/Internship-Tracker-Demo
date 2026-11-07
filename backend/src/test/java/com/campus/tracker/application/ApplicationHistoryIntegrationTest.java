package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ApplicationRepository applications;
    @Autowired JdbcTemplate jdbc;

    @Test void historyDoesNotSkipOrRepeatUnreadRowsWhenAnEarlierRowIsDeletedAndALaterRowIsEdited() throws Exception {
        CampusPrincipal owner = owner();
        List<InternshipApplication> records = records(owner, 205);
        // Every timestamp is equal; IDs on both sides of the UUID sign bit test the tie-breaker.
        records.sort(Comparator.comparing(a -> a.id.toString()));
        JsonNode first = page(owner, null, 100);
        assertThat(first.path("content").size()).isEqualTo(100);
        List<String> loaded = ids(first);
        assertThat(loaded).containsExactlyElementsOf(records.subList(0, 100).stream().map(a -> a.id.toString()).toList());
        jdbc.update("UPDATE applications SET deleted=TRUE, version=version+1 WHERE id=?", records.get(0).id);
        jdbc.update("UPDATE applications SET company=?, updated_at=?, version=version+1 WHERE id=?",
                "Edited while loading", java.sql.Timestamp.from(Instant.now()), records.get(204).id);
        // A new row beyond the initial upper bound must wait for the next refresh.
        InternshipApplication newer = record(owner, UUID.randomUUID(), Instant.parse("2026-02-01T00:00:00Z"), "Added while loading");
        applications.saveAndFlush(newer);

        JsonNode second = page(owner, first.path("nextCursor").asText(), 100);
        assertThat(second.path("content").size()).isEqualTo(100);
        loaded.addAll(ids(second));
        JsonNode last = page(owner, second.path("nextCursor").asText(), 100);
        loaded.addAll(ids(last));
        assertThat(last.path("content").size()).isEqualTo(5);
        assertThat(last.path("nextCursor").isNull()).isTrue();
        assertThat(loaded).containsExactlyElementsOf(records.stream().map(a -> a.id.toString()).toList());
        assertThat(loaded).doesNotHaveDuplicates().doesNotContain(newer.id.toString());
        assertThat(last.path("content").get(4).path("company").asText()).isEqualTo("Edited while loading");
        // Traversal retains a row read before deletion; a fresh traversal sees current visibility.
        assertThat(ids(page(owner, null, 100))).doesNotContain(records.get(0).id.toString());
    }

    @Test void deletedUnreadRowsDoNotPreventCompletionAndADeletedUpperBoundStillTerminates() throws Exception {
        CampusPrincipal owner = owner();
        List<InternshipApplication> records = records(owner, 3);
        records.sort(Comparator.comparing(a -> a.id.toString()));
        JsonNode first = page(owner, null, 1);
        jdbc.update("UPDATE applications SET deleted=TRUE WHERE id IN (?,?)", records.get(1).id, records.get(2).id);
        JsonNode last = page(owner, first.path("nextCursor").asText(), 1);
        assertThat(last.path("content").isEmpty()).isTrue();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }

    @Test void historyIsOwnerScopedAndRejectsAnotherOwnersCursor() throws Exception {
        CampusPrincipal owner = owner(), other = owner();
        records(owner, 2);
        List<InternshipApplication> otherRecords = records(other, 1);
        JsonNode first = page(owner, null, 1);
        assertThat(ids(first)).doesNotContain(otherRecords.get(0).id.toString());
        mvc.perform(get("/api/applications/history").with(user(other)).param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isBadRequest());
        assertThat(ids(page(other, null, 100))).containsExactly(otherRecords.get(0).id.toString());
        mvc.perform(get("/api/applications/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/applications/history").with(user(other)).header("X-Campus-Account", owner.id().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
    }

    @Test void pageAndCursorInputsAreBoundedAndEmptyHistoryTerminates() throws Exception {
        CampusPrincipal owner = owner();
        JsonNode empty = page(owner, null, 100);
        assertThat(empty.path("content").isEmpty()).isTrue();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        for (String size : List.of("0", "101", "-1", "invalid")) {
            mvc.perform(get("/api/applications/history").with(user(owner)).param("size", size)).andExpect(status().isBadRequest());
        }
        for (String cursor : List.of("", "not-a-cursor", "*", "A".repeat(513))) {
            mvc.perform(get("/api/applications/history").with(user(owner)).param("cursor", cursor)).andExpect(status().isBadRequest());
        }
    }

    private CampusPrincipal owner() {
        CampusUser account = new CampusUser("history-" + UUID.randomUUID() + "@example.test", "History User", "unused-test-hash");
        account.verifyEmail(Instant.now());
        return CampusPrincipal.from(users.saveAndFlush(account));
    }

    private List<InternshipApplication> records(CampusPrincipal owner, int count) {
        List<InternshipApplication> records = new ArrayList<>();
        // Keep IDs unique across accounts while crossing the signed UUID comparison boundary.
        long suffix = UUID.randomUUID().getLeastSignificantBits();
        for (int i = 0; i < count; i++) records.add(record(owner, new UUID((i % 2 == 0 ? Long.MIN_VALUE : 0) + i, suffix),
                Instant.parse("2026-01-01T00:00:00.123456Z"), "History company " + i));
        return applications.saveAllAndFlush(records);
    }

    private InternshipApplication record(CampusPrincipal owner, UUID id, Instant createdAt, String company) {
        InternshipApplication record = new InternshipApplication(owner.id());
        record.id = id; record.createdAt = createdAt; record.updatedAt = createdAt;
        record.company = company; record.role = "Intern"; record.location = "";
        record.status = ApplicationStatus.SAVED; record.notes = "";
        return record;
    }

    private JsonNode page(CampusPrincipal owner, String cursor, int size) throws Exception {
        var request = get("/api/applications/history").with(user(owner)).param("size", Integer.toString(size));
        if (cursor != null) request.param("cursor", cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("content").forEach(record -> ids.add(record.path("id").asText()));
        return ids;
    }
}
