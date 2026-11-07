package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Resolve an uncertain create without replaying a write; cancellation also fences delayed original requests. */
@RestController
@RequestMapping("/api/application-creates")
public class ApplicationCreateController {
    private final ApplicationCreateService service;
    public ApplicationCreateController(ApplicationCreateService service) { this.service = service; }

    @GetMapping("/{key}")
    public ResponseEntity<ApplicationCreateService.Outcome> get(@AuthenticationPrincipal CampusPrincipal user, @PathVariable String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(user.id(), key));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<ApplicationCreateService.Outcome> cancel(@AuthenticationPrincipal CampusPrincipal user, @PathVariable String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(user.id(), key));
    }
}
