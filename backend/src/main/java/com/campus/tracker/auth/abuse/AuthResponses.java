package com.campus.tracker.auth.abuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class AuthResponses {
    static void throttled(HttpServletResponse response, ObjectMapper json, long seconds) throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, seconds)));
        error(response, json, 429, AuthThrottleException.MESSAGE);
    }
    static void error(HttpServletResponse response, ObjectMapper json, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("message", message));
    }
    private AuthResponses() {}
}
