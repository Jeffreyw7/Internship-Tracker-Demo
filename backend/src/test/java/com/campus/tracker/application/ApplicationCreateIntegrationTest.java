package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:application-create-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationCreateIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test void retryAfterLostResponseReturnsOnePersistedApplication() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        JsonNode first = create(owner, key, draft(), 201);
        JsonNode repeated = create(owner, key, draft(), 201);
        assertThat(repeated).isEqualTo(first);
        assertThat(count(owner)).isEqualTo(1);
        mvc.perform(get("/api/application-creates/{key}", key).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.application.id").value(first.path("id").asText()));
    }

    @Test void changedPayloadCannotReuseACompletedKey() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        create(owner, key, draft(), 201);
        assertThat(create(owner, key, draft().put("notes", "Different intent"), 409).path("code").asText())
                .isEqualTo("CREATE_KEY_REUSED");
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test void fingerprintUsesNormalizedValuesAndIgnoresUnownedStageIds() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        ObjectNode initial = draft();
        initial.put("company", "  Example  ");
        initial.putArray("stages").addObject().put("name", "  Call  ").put("state", "Planned").put("id", UUID.randomUUID().toString());
        JsonNode first = create(owner, key, initial, 201);
        ObjectNode normalized = draft();
        normalized.putArray("stages").addObject().put("name", "Call").put("state", "Planned");
        assertThat(create(owner, key, normalized, 201)).isEqualTo(first);
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test void replayAndOutcomeReturnCurrentEditedAndDeletedRecord() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        JsonNode first = create(owner, key, draft(), 201);
        JsonNode edited = body(mvc.perform(put("/api/applications/{id}", first.path("id").asText()).with(user(owner)).with(csrf())
                .header("If-Match", first.path("version").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draft().put("notes", "Edited later")))).andExpect(status().isOk()).andReturn());
        assertThat(create(owner, key, draft(), 201)).isEqualTo(edited);
        JsonNode deleted = body(mvc.perform(delete("/api/applications/{id}", first.path("id").asText()).with(user(owner)).with(csrf())
                .header("If-Match", edited.path("version").asText())).andExpect(status().isOk()).andReturn());
        assertThat(create(owner, key, draft(), 201)).isEqualTo(deleted);
        JsonNode result = outcome(owner, key, false, 200);
        assertThat(result.path("application")).isEqualTo(deleted);
        assertThat(outcome(owner, key, true, 200)).isEqualTo(result);
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test void cancelledKeyPreventsLateOriginalCreateAndCancellationIsRepeatable() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        assertThat(outcome(owner, key, false, 404).path("code").asText()).isEqualTo("CREATE_NOT_FOUND");
        JsonNode cancelled = outcome(owner, key, true, 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("application").isNull()).isTrue();
        assertThat(outcome(owner, key, true, 200)).isEqualTo(cancelled);
        assertThat(outcome(owner, key, false, 200)).isEqualTo(cancelled);
        assertThat(create(owner, key, draft(), 409).path("code").asText()).isEqualTo("CREATE_CANCELLED");
        assertThat(count(owner)).isZero();
    }

    @Test void keysAndCancellationAreScopedToAuthenticatedOwner() throws Exception {
        var first = owner(); var second = owner(); String key = UUID.randomUUID().toString();
        JsonNode created = create(first, key, draft(), 201);
        outcome(second, key, false, 404);
        assertThat(outcome(second, key, true, 200).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(create(first, key, draft(), 201)).isEqualTo(created);
        String shared = UUID.randomUUID().toString();
        assertThat(create(first, shared, draft(), 201).path("id"))
                .isNotEqualTo(create(second, shared, draft(), 201).path("id"));
    }

    @Test void malformedKeysAreRejectedAndNoKeyRequestsRemainIndependent() throws Exception {
        var owner = owner();
        for (String invalid : List.of("", "not-a-uuid", "1-1-1-1-1", UUID.randomUUID() + "extra")) {
            assertThat(create(owner, invalid, draft(), 400).path("code").asText()).isEqualTo("INVALID_CREATE_KEY");
        }
        outcome(owner, "1-1-1-1-1", false, 400);
        outcome(owner, "not-a-uuid", true, 400);
        assertThat(create(owner, null, draft(), 201).path("id"))
                .isNotEqualTo(create(owner, null, draft(), 201).path("id"));
        assertThat(count(owner)).isEqualTo(2);
    }

    @Test void simultaneousRetriesCommitOneApplication() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        var results = race(() -> create(owner, key, draft(), 201), () -> create(owner, key, draft(), 201));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test void racingCancellationAndCreateHaveOneDurableOutcome() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        var results = race(() -> mvc.perform(post("/api/applications").with(user(owner)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft()))).andReturn(),
                () -> outcome(owner, key, true, 200));
        JsonNode finalOutcome = outcome(owner, key, false, 200);
        assertThat(results.get(1)).isEqualTo(finalOutcome);
        MvcResult createResponse = (MvcResult) results.get(0);
        if (finalOutcome.path("status").asText().equals("CANCELLED")) {
            assertThat(createResponse.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(createResponse).path("code").asText()).isEqualTo("CREATE_CANCELLED");
            assertThat(count(owner)).isZero();
        } else {
            assertThat(createResponse.getResponse().getStatus()).isEqualTo(201);
            assertThat(finalOutcome.path("application")).isEqualTo(body(createResponse));
            assertThat(count(owner)).isEqualTo(1);
        }
    }

    @Test void cancellationRequiresCsrfAndAnUnchangedAccountBoundary() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        mvc.perform(delete("/api/application-creates/{key}", key).with(user(owner))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/application-creates/{key}", key).with(user(owner)).with(csrf())
                .header("X-Campus-Account", UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        outcome(owner, key, false, 404);
    }

    @Test void failedReceiptInsertRollsBackApplicationAndStagesThenAllowsRetry() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        String constraint = "reject_create_" + key.replace("-", "");
        ObjectNode input = draft(); input.putArray("stages").addObject().put("name", "Call").put("state", "Planned");
        jdbc.execute("alter table application_create_receipts add constraint " + constraint + " check (idempotency_key <> '" + key + "')");
        try { create(owner, key, input, 409); }
        finally { jdbc.execute("alter table application_create_receipts drop constraint " + constraint); }
        assertThat(count(owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application_create_receipts where owner_id=?", Long.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from interview_stages s join applications a on a.id=s.application_id where a.owner_id=?", Long.class, owner.id())).isZero();
        outcome(owner, key, false, 404);
        assertThat(create(owner, key, input, 201).path("stages")).hasSize(1);
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test void invalidDraftDoesNotConsumeTheKey() throws Exception {
        var owner = owner(); String key = UUID.randomUUID().toString();
        create(owner, key, draft().put("status", "Applied"), 400);
        outcome(owner, key, false, 404);
        create(owner, key, draft(), 201);
        assertThat(count(owner)).isEqualTo(1);
    }

    private CampusPrincipal owner() {
        return CampusPrincipal.from(users.saveAndFlush(new CampusUser(UUID.randomUUID() + "@example.test", "Create test", "unused")));
    }
    private ObjectNode draft() {
        ObjectNode result = json.createObjectNode().put("company", "Example").put("role", "Intern").put("location", "Remote")
                .put("status", "Saved").put("notes", "Original intent");
        result.putArray("stages"); return result;
    }
    private JsonNode create(CampusPrincipal owner, String key, ObjectNode input, int expectedStatus) throws Exception {
        var request = post("/api/applications").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input));
        if (key != null) request.header("Idempotency-Key", key);
        return body(mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode outcome(CampusPrincipal owner, String key, boolean cancel, int expectedStatus) throws Exception {
        var request = cancel ? delete("/api/application-creates/{key}", key).with(csrf()) : get("/api/application-creates/{key}", key);
        return body(mvc.perform(request.with(user(owner))).andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private long count(CampusPrincipal owner) { return jdbc.queryForObject("select count(*) from applications where owner_id=?", Long.class, owner.id()); }
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (Callable<?> action : List.of(first, second)) results.add(pool.submit(() -> { start.await(); return action.call(); }));
            start.countDown(); return List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
