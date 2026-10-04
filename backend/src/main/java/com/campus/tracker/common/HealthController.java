// Provides the lightweight application marker used by the launcher and smoke test; it is not a database probe.
package com.campus.tracker.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public Map<String, String> health() { return Map.of("status", "UP", "application", "campus-tracker"); }
}
