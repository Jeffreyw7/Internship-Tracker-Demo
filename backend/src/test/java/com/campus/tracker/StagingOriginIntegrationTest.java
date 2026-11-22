package com.campus.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "STAGING_ORIGIN_KEY=" + StagingOriginIntegrationTest.KEY)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StagingOriginIntegrationTest {
    static final String KEY = "fixture-for-the-in-memory-origin-test";
    @Autowired MockMvc mvc;

    @Test void requiresTheIngressKeyBeforeCreatingSessionsAndLeavesTheHealthCheckReachable() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/auth/csrf").header("X-Campus-Origin-Key", KEY, KEY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("X-Campus-Origin-Key", KEY)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("X-Campus-Origin-Key", KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
