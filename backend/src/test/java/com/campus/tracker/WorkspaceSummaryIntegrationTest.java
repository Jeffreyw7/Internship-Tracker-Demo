package com.campus.tracker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceSummaryIntegrationTest extends WorkspaceReadTestSupport {
    @Test void fullAccountSummaryCountsStatusesSamplesAndActivitiesIndependentlyOfPage() throws Exception {
        var date = LocalDate.parse("2026-11-01");
        for (String status : List.of("SAVED", "APPLIED", "INTERVIEWING", "OFFER", "REJECTED", "WITHDRAWN")) {
            var id = insert(status, status, null, date, date);
            jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,0,'Round',?,'SCHEDULED')", UUID.randomUUID(), id, date);
            jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,1,'Complete',?,'COMPLETED')", UUID.randomUUID(), id, date);
        }
        for (int i = 0; i < 4; i++) insert("extra", "APPLIED", null, null, null);
        var hidden = insert("hidden", "APPLIED", null, date, date);
        jdbc.update("UPDATE applications SET deleted=true WHERE id=?", hidden);
        jdbc.update("UPDATE applications SET sample=true WHERE owner_id=? AND status='SAVED'", owner);
        assertThat(page(request()).get("content")).hasSize(8);
        var summary = json.readTree(mvc.perform(request().with(req -> { req.setRequestURI("/api/v2/applications/summary"); return req; }).param("today", date.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.get("total").asLong()).isEqualTo(10);
        assertThat(summary.get("active").asLong()).isEqualTo(6);
        assertThat(summary.get("interviews").asLong()).isEqualTo(1);
        assertThat(summary.get("offers").asLong()).isEqualTo(1);
        assertThat(summary.get("due").asLong()).isEqualTo(4);
        assertThat(summary.get("scheduledInterviews").asLong()).isEqualTo(4);
        assertThat(summary.get("hasSamples").asBoolean()).isTrue();
        assertThat(summary.get("perStatus").get("Applied").asLong()).isEqualTo(5);
        for (String status : List.of("Saved", "Interviewing", "Offer", "Rejected", "Withdrawn")) assertThat(summary.get("perStatus").get(status).asLong()).isEqualTo(1);
        assertThat(summary.get("readAt").asText()).isNotBlank();
    }
}
