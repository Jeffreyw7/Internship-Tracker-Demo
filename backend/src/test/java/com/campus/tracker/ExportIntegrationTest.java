package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExportIntegrationTest extends WorkspaceReadTestSupport {
    @Test void exportCreationIsDurableOwnerScopedAndLimitedToTwoActiveJobs() throws Exception {
        var principal = new CampusPrincipal(owner, "Test", owner + "@example.test", null, true);
        mvc.perform(post("/api/v2/exports").with(user(principal))).andExpect(status().isForbidden());
        var first = json.readTree(mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String id = first.get("id").asText();
        assertThat(first.get("state").asText()).isEqualTo("PENDING");
        assertThat(first.has("ownerId")).isFalse(); assertThat(first.has("objectKey")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_jobs WHERE id=?", Integer.class, UUID.fromString(id))).isEqualTo(1);
        mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf())).andExpect(status().isAccepted());
        mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf())).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v2/exports/" + id).with(user(principal))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        mvc.perform(get("/api/v2/exports/" + id + "/download").with(user(principal))).andExpect(status().isConflict());
        var stranger = new CampusPrincipal(UUID.randomUUID(), "Other", "other@example.test", null, true);
        mvc.perform(get("/api/v2/exports/" + id).with(user(stranger))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v2/exports/" + id).with(user(stranger)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/exports/" + id + "/download").with(user(stranger))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v2/exports/" + id).with(user(principal))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v2/exports/" + id).with(user(principal)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(delete("/api/v2/exports/" + id).with(user(principal)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf())).andExpect(status().isAccepted());
        mvc.perform(get("/api/v2/exports/" + UUID.randomUUID()).with(user(principal))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/exports/" + id)).andExpect(status().isUnauthorized());
    }
    @Test void expiredJobsCannotDownloadAndNeverHoldAnActiveQuotaSlot() throws Exception {
        var principal = new CampusPrincipal(owner, "Test", owner + "@example.test", null, true);
        var result = json.readTree(mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String id = result.get("id").asText();
        jdbc.update("UPDATE export_jobs SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1' HOUR WHERE id=?", UUID.fromString(id));
        mvc.perform(get("/api/v2/exports/" + id).with(user(principal))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EXPIRED"));
        mvc.perform(get("/api/v2/exports/" + id + "/download").with(user(principal))).andExpect(status().isGone());
        mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf())).andExpect(status().isAccepted());
        mvc.perform(post("/api/v2/exports").with(user(principal)).with(csrf())).andExpect(status().isAccepted());
    }
}
