package com.campus.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "campus.reminders.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReminderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @BeforeEach void time() { when(clock.instant()).thenReturn(Instant.parse("2026-10-02T08:00:00Z")); }

    MockHttpSession account(boolean samples) throws Exception {
        String email = "reminder-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "Campus-test-123!", "displayName", "Reminder Test", "includeSamples", samples))))
            .andExpect(status().isCreated());
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", "Campus-test-123!"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    Map<String,Object> preferences() {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("enabled",true), Map.entry("interviewsEnabled",true),
            Map.entry("deadlinesEnabled",true),Map.entry("followUpsEnabled",true),Map.entry("interviewLeadDays",1),
            Map.entry("deadlineLeadDays",3),Map.entry("followUpLeadDays",0),Map.entry("timezone","UTC"),
            Map.entry("reminderTime","09:00"),Map.entry("deliveryMode","IMMEDIATE"),Map.entry("digestTime","09:00"),Map.entry("emailEnabled",false)));
    }
    JsonNode overview(MockHttpSession s) throws Exception {
        return body(mvc.perform(get("/api/reminders").session(s)).andExpect(status().isOk()).andReturn());
    }
    JsonNode enable(MockHttpSession s) throws Exception {
        String version = overview(s).get("preferences").get("version").asText();
        return body(mvc.perform(put("/api/reminders/preferences").session(s).with(csrf()).header("If-Match", version)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(preferences())))
            .andExpect(status().isOk()).andReturn());
    }
    Map<String,Object> draft(String status) {
        Map<String,Object> d = new LinkedHashMap<>();
        d.put("company","Reminder Labs"); d.put("role","Java Intern"); d.put("location",""); d.put("status",status);
        d.put("appliedDate", status.equals("Saved") ? null : LocalDate.now().minusDays(1).toString());
        d.put("deadline","2026-10-05"); d.put("followUpDate","2026-10-02"); d.put("notes","");
        d.put("stages",List.of(Map.of("name","Technical interview","date","2026-10-03","state","Scheduled")));
        return d;
    }
    JsonNode create(MockHttpSession s, Map<String,Object> draft) throws Exception {
        return body(mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(draft))).andExpect(status().isCreated()).andReturn());
    }
    JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    JsonNode kind(JsonNode overview, String kind) {
        for (JsonNode r : overview.get("reminders")) if (r.get("kind").asText().equals(kind)) return r;
        throw new AssertionError("Missing reminder " + kind);
    }

    @Test void defaultOptInSecurityValidationAndVersions() throws Exception {
        mvc.perform(get("/api/reminders")).andExpect(status().isUnauthorized());
        var s=account(false); var initial=overview(s);
        assertThat(initial.get("preferences").get("enabled").asBoolean()).isFalse();
        assertThat(initial.get("emailAvailable").asBoolean()).isFalse();
        mvc.perform(put("/api/reminders/preferences").session(s).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(preferences()))).andExpect(status().isForbidden());
        mvc.perform(put("/api/reminders/preferences").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(preferences()))).andExpect(status().isPreconditionRequired());
        var enabled=enable(s);
        assertThat(enabled.get("preferences").get("version").asLong()).isEqualTo(1);
        mvc.perform(put("/api/reminders/preferences").session(s).with(csrf()).header("If-Match","0").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(preferences()))).andExpect(status().isConflict());
        for (var invalid : List.of(Map.entry("timezone",(Object)"Mars/Olympus"),Map.entry("reminderTime",(Object)"25:00"),
                Map.entry("interviewLeadDays",(Object)31),Map.entry("emailEnabled",(Object)true),Map.entry("deliveryMode",(Object)"WEEKLY"))) {
            var p=preferences();p.put(invalid.getKey(),invalid.getValue());
            mvc.perform(put("/api/reminders/preferences").session(s).with(csrf()).header("If-Match","1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(p))).andExpect(status().isBadRequest());
        }
    }

    @Test void sourceEligibilityAndRepeatedReadsDoNotDuplicate() throws Exception {
        var s=account(false); enable(s); create(s,draft("Interviewing")); create(s,draft("Saved")); create(s,draft("Offer"));
        var result=overview(s);
        assertThat(result.get("reminders")).hasSize(3);
        assertThat(kind(result,"INTERVIEW").get("dueAt").asText()).isEqualTo("2026-10-02T09:00:00Z");
        assertThat(kind(result,"DEADLINE").get("eventDate").asText()).isEqualTo("2026-10-05");
        assertThat(kind(result,"FOLLOW_UP").get("dueAt").asText()).isEqualTo("2026-10-02T09:00:00Z");
        assertThat(overview(s).get("reminders")).isEqualTo(result.get("reminders"));
        var samples=account(true);enable(samples);assertThat(overview(samples).get("reminders")).isEmpty();
    }

    @Test void snoozeDismissAndOwnershipSurviveReconciliation() throws Exception {
        var s=account(false);enable(s);create(s,draft("Interviewing"));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-02T10:00:00Z"));
        var item=kind(overview(s),"INTERVIEW"); String id=item.get("id").asText();
        var other=account(false);
        mvc.perform(post("/api/reminders/"+id+"/snooze").session(other).with(csrf()).header("If-Match",item.get("version").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":60}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/reminders/"+id+"/snooze").session(s).with(csrf()).header("If-Match",item.get("version").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":1}")).andExpect(status().isBadRequest());
        var snoozed=body(mvc.perform(post("/api/reminders/"+id+"/snooze").session(s).with(csrf()).header("If-Match",item.get("version").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":60}")).andExpect(status().isOk()).andReturn());
        assertThat(snoozed.get("dueAt").asText()).isEqualTo("2026-10-02T11:00:00Z");
        assertThat(kind(overview(s),"INTERVIEW").get("dueAt")).isEqualTo(snoozed.get("dueAt"));
        mvc.perform(post("/api/reminders/"+id+"/dismiss").session(s).with(csrf()).header("If-Match",item.get("version").asText()))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/reminders/"+id+"/dismiss").session(s).with(csrf()).header("If-Match",snoozed.get("version").asText()))
            .andExpect(status().isOk());
        assertThat(overview(s).get("reminders")).hasSize(1);
    }

    @Test void editsCompletionAndDeleteCancelOldOccurrences() throws Exception {
        var s=account(false);enable(s);var d=draft("Interviewing");var app=create(s,d);
        var old=kind(overview(s),"INTERVIEW");
        d.put("followUpDate",null); d.put("stages",List.of(Map.of("id",app.get("stages").get(0).get("id").asText(),
            "name","Technical interview","date","2026-10-04","state","Scheduled")));
        app=body(mvc.perform(put("/api/applications/"+app.get("id").asText()).session(s).with(csrf())
            .header("If-Match",app.get("version").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d)))
            .andExpect(status().isOk()).andReturn());
        var updated=overview(s);assertThat(updated.get("reminders")).hasSize(1);
        assertThat(kind(updated,"INTERVIEW").get("id")).isNotEqualTo(old.get("id"));
        mvc.perform(delete("/api/applications/"+app.get("id").asText()).session(s).with(csrf()).header("If-Match",app.get("version").asText()))
            .andExpect(status().isOk());
        assertThat(overview(s).get("reminders")).isEmpty();
    }
}
