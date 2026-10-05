package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ActivityReadIntegrationTest extends WorkspaceReadTestSupport {
    @Test void feedHasDeterministicPagingAndPreservesStageSearchAndClosedSemantics() throws Exception {
        var date = LocalDate.parse("2026-11-01");
        for (String status : List.of("SAVED", "APPLIED", "INTERVIEWING", "OFFER", "REJECTED", "WITHDRAWN")) {
            var id = insert(status, status, null, date, date);
            jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,0,'Systems Round',?,'SCHEDULED')", UUID.randomUUID(), id, date);
        }
        var visited = new HashSet<String>(); String cursor = null;
        do {
            var query = get("/api/v2/activities").with(user(new CampusPrincipal(owner, "Test", owner + "@example.test", null, true))).param("size", "2");
            if (cursor != null) query.param("cursor", cursor);
            var response = page(query);
            for (var activity : response.get("content")) {
                assertThat(visited.add(activity.get("id").asText())).isTrue();
                assertThat(activity.has("notes")).isFalse(); assertThat(activity.has("stages")).isFalse();
                assertThat(activity.get("applicationStatus").asText()).isNotIn("Rejected", "Withdrawn");
            }
            cursor = response.get("nextCursor").isNull() ? null : response.get("nextCursor").asText();
        } while (cursor != null);
        assertThat(visited).hasSize(9); // Four interviews, four follow-ups and one Saved deadline.
        var query = get("/api/v2/activities").with(user(new CampusPrincipal(owner, "Test", owner + "@example.test", null, true)));
        assertThat(page(query.param("q", "systems").param("kind", "Interview")).get("content")).hasSize(4);
        mvc.perform(get("/api/v2/activities").with(user(new CampusPrincipal(owner, "Test", owner + "@example.test", null, true))).param("kind", "invalid")).andExpect(status().isBadRequest());
    }
}
