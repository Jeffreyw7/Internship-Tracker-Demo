package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ApplicationHistoryController {
    private final ApplicationHistoryService service;
    public ApplicationHistoryController(ApplicationHistoryService service) { this.service = service; }

    @GetMapping("/api/applications/history")
    public ApplicationHistoryService.HistoryPage list(@AuthenticationPrincipal CampusPrincipal user,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        return service.list(user.id(), size, cursor);
    }
}
