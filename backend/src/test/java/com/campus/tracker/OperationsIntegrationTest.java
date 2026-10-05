package com.campus.tracker;

import javax.sql.DataSource;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"management.endpoint.health.cache.time-to-live=0",
        "campus.operations.metrics-token=test-only-metrics-token"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class OperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean DataSource dataSource;

    @Test void probesArePublicAndDoNotExposeInternals() throws Exception {
        mvc.perform(get("/livez")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/readyz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test void managementInformationIsPrivateEvenForOrdinaryUsers() throws Exception {
        for (String path : new String[]{"env", "metrics", "prometheus"}) {
            mvc.perform(get("/actuator/" + path)).andExpect(status().isForbidden());
            mvc.perform(get("/actuator/" + path).with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("member")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-only-metrics-token"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer test-only-metrics-token"))
                .andExpect(status().isForbidden());
    }

    @Test void databaseFailureRemovesReadinessButKeepsLiveness() throws Exception {
        doThrow(new SQLException("private database endpoint")).when(dataSource).getConnection();
        try {
            mvc.perform(get("/readyz")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/livez")).andExpect(status().isOk());
        } finally { reset(dataSource); }
    }

    @Test void apiResponsesAreNotCachedAndCorrelationCannotContainUnsafeInput() throws Exception {
        mvc.perform(get("/api/health").header("X-Request-ID", "unsafe value"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.matchesPattern("[a-zA-Z0-9_-]{1,64}")));
        mvc.perform(get("/api/health").header("X-Request-ID", "trace-123"))
                .andExpect(header().string("X-Request-ID", "trace-123"));
    }
}
