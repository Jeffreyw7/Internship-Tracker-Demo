package com.campus.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.campus.tracker.reminder.ReminderService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class PostingDetailsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ReminderService reminders;
    MockHttpSession account() throws Exception {
        String email = "posting-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "Campus-test-123!",
                        "displayName", "Posting Tester", "includeSamples", false)))).andExpect(status().isCreated());
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("email", email)
                .param("password", "Campus-test-123!")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    Map<String,Object> posting() {
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("postingUrl", "https://example.test/jobs/123"); p.put("recruiterName", "Alex Chen");
        p.put("recruiterRole", "Recruiter"); p.put("recruiterEmail", "alex@example.test");
        p.put("recruiterPhone", "+1 (212) 555-0100"); p.put("recruiterProfileUrl", "https://example.test/alex");
        p.put("referralChannel", "EMPLOYEE"); p.put("referralSource", "Engineering team");
        p.put("referrerName", "Sam"); p.put("referrerContact", "sam@example.test");
        p.put("compensationStatus", "PAID"); p.put("compensationMin", "25.10"); p.put("compensationMax", "35.50");
        p.put("compensationCurrency", "USD"); p.put("compensationPeriod", "HOUR");
        p.put("compensationOriginalText", "$25.10–35.50/hour plus housing");
        p.put("descriptionSnapshot", "Build Java APIs\n<script>alert('text only')</script>\nUnicode: café 😀");
        return p;
    }
    Map<String,Object> draft() {
        return new LinkedHashMap<>(Map.of("company", "Archive Labs", "role", "Intern", "location", "Remote",
                "status", "Saved", "notes", "Private notes", "stages", List.of()));
    }
    JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    JsonNode create(MockHttpSession s, Map<String,Object> p) throws Exception {
        var d = draft(); if (p != null) d.put("postingDetails", p);
        return body(mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(d))).andExpect(status().isCreated()).andReturn());
    }
    String path(JsonNode app) { return "/api/applications/" + app.get("id").asText(); }
    JsonNode update(MockHttpSession s, JsonNode app, Map<String,Object> d) throws Exception {
        return body(mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match", app.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isOk()).andReturn());
    }
    @Test void captureHistoryAndOldClientUpdatesPreserveTheArchive() throws Exception {
        var s = account(); var p = posting(); JsonNode app = create(s,p);
        assertThat(app.at("/postingDetails/compensationMin").asText()).isEqualTo("25.10");
        assertThat(app.at("/postingDetails/descriptionRevision").asInt()).isEqualTo(1);
        mvc.perform(get("/api/applications").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].postingDetails.descriptionSnapshot").doesNotExist());
        JsonNode current = body(mvc.perform(get(path(app)+"/posting-description").session(s))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn());
        assertThat(current.at("/snapshot/descriptionText").asText()).isEqualTo(p.get("descriptionSnapshot"));
        app = update(s,app,draft());
        assertThat(app.at("/postingDetails/recruiterName").asText()).isEqualTo("Alex Chen");
        var d = draft(); p.put("descriptionSnapshot","Updated description"); d.put("postingDetails",p);
        app = update(s,app,d); app = update(s,app,d);
        assertThat(app.at("/postingDetails/descriptionRevision").asInt()).isEqualTo(2);
        p.remove("descriptionSnapshot"); p.put("postingUrl","https://example.test/gone");
        app = update(s,app,d);
        assertThat(app.at("/postingDetails/descriptionRevision").asInt()).isEqualTo(2);
        p.put("descriptionSnapshot",""); app = update(s,app,d);
        assertThat(app.at("/postingDetails/descriptionRevision").asInt()).isEqualTo(3);
        mvc.perform(get(path(app)+"/posting-descriptions").session(s).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].revision").value(3)).andExpect(jsonPath("$.content[0].descriptionText").doesNotExist());
        mvc.perform(get(path(app)+"/posting-descriptions/"+current.at("/snapshot/id").asText()).session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceUrl").value("https://example.test/jobs/123"));
    }
    @Test void defaultsEmptyFirstCaptureAndExplicitNullAreDistinct() throws Exception {
        var s=account(); var app=create(s,null);
        assertThat(app.at("/postingDetails/compensationStatus").asText()).isEqualTo("UNKNOWN");
        mvc.perform(get(path(app)+"/posting-description").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.snapshot").isEmpty());
        var p=posting(); p.put("descriptionSnapshot",""); var d=draft(); d.put("postingDetails",p);
        app=update(s,app,d); assertThat(app.at("/postingDetails/descriptionRevision").asInt()).isZero();
        for (Object invalid : Arrays.asList(null, Map.of(), Map.of("descriptionSnapshot","bad"))) {
            d.put("postingDetails",invalid);
            mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
        }
        p.put("descriptionSnapshot",null); d.put("postingDetails",p);
        mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
    }
    @Test void snapshotReadsAreScopedAndDeletionUndoPreservesHistory() throws Exception {
        var owner=account(); var other=account(); var app=create(owner,posting());
        var current=body(mvc.perform(get(path(app)+"/posting-description").session(owner)).andExpect(status().isOk()).andReturn());
        String suffix="/posting-descriptions/"+current.at("/snapshot/id").asText();
        for (String route : List.of("/posting-description","/posting-descriptions",suffix)) {
            mvc.perform(get(path(app)+route)).andExpect(status().isUnauthorized());
            mvc.perform(get(path(app)+route).session(other)).andExpect(status().isNotFound());
        }
        var another=create(owner,posting());
        mvc.perform(get(path(another)+suffix).session(owner)).andExpect(status().isNotFound());
        var deleted=body(mvc.perform(delete(path(app)).session(owner).with(csrf()).header("If-Match",app.get("version").asLong()))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get(path(app)+suffix).session(owner)).andExpect(status().isNotFound());
        mvc.perform(post(path(app)+"/restore").session(owner).with(csrf()).header("If-Match",deleted.get("version").asLong())).andExpect(status().isOk());
        mvc.perform(get(path(app)+suffix).session(owner)).andExpect(status().isOk());
        mvc.perform(get(path(app)+"/posting-descriptions").session(owner).param("size","51")).andExpect(status().isBadRequest());
    }
    @Test void invalidPostingInputAndStaleWritesLeaveNoPartialChanges() throws Exception {
        var s=account(); var app=create(s,posting());
        for (var invalid : List.of(Map.of("postingUrl","javascript:alert(1)"), Map.of("postingUrl","https://user:pass@example.test"),
                Map.of("recruiterEmail","invalid"),Map.of("compensationMin","-1"),Map.of("compensationMax","24.00"),
                Map.of("compensationMin","25.001"),Map.of("compensationCurrency","ZZZ"),Map.of("compensationStatus","UNPAID"),
                Map.of("descriptionSnapshot","x".repeat(100001)),Map.of("descriptionRevision",99))) {
            var p=posting(); p.putAll(invalid); var d=draft(); d.put("company","Must roll back"); d.put("postingDetails",p);
            mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
        }
        var d=draft(); var p=posting(); p.put("descriptionSnapshot","Newest"); d.put("postingDetails",p);
        update(s,app,d);
        mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isConflict());
        mvc.perform(get(path(app)+"/posting-descriptions").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(path(app)).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.company").value("Archive Labs"));
    }
    @Test void simultaneousSnapshotWritersCommitOnlyOneRevision() throws Exception {
        var s=account();var app=create(s,posting());
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(String text:List.of("Concurrent A","Concurrent B"))results.add(pool.submit(()->{
                var d=draft();var p=posting();p.put("descriptionSnapshot",text);d.put("postingDetails",p);
                ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                return mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        mvc.perform(get(path(app)+"/posting-descriptions").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }
    @Test void failureAfterSnapshotInsertionRollsBackParentAndSnapshot() throws Exception {
        var s=account();var app=create(s,posting());var d=draft();var p=posting();
        p.put("descriptionSnapshot","Must roll back");d.put("postingDetails",p);d.put("company","Must roll back");
        doThrow(new IllegalStateException("synthetic after-snapshot failure")).when(reminders).reconcile(any(UUID.class));
        assertThatThrownBy(()->mvc.perform(put(path(app)).session(s).with(csrf()).header("If-Match",app.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))))
                .hasRootCauseMessage("synthetic after-snapshot failure");
        mvc.perform(get(path(app)).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.company").value("Archive Labs"))
                .andExpect(jsonPath("$.version").value(app.get("version").asInt())).andExpect(jsonPath("$.postingDetails.descriptionRevision").value(1));
        mvc.perform(get(path(app)+"/posting-descriptions").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
