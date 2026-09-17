package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.*;

@RestController
@RequestMapping("/api/applications")
@Validated
public class ApplicationController {
    private final ApplicationService service;
    public ApplicationController(ApplicationService service) { this.service = service; }

    @GetMapping
    public ApplicationPage list(@AuthenticationPrincipal CampusPrincipal user,
            @RequestParam(defaultValue = "") @Size(max = 200) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "updated") String sort) {
        return service.list(user.id(), q, status, page, size, sort);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApplicationView> get(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id) {
        ApplicationView app = service.get(user.id(), id);
        return ResponseEntity.ok().eTag(Long.toString(app.version())).body(app);
    }

    @PostMapping
    public ResponseEntity<ApplicationView> create(@AuthenticationPrincipal CampusPrincipal user,
                                                  @Valid @RequestBody ApplicationRequest input) {
        ApplicationView app = service.create(user.id(), input);
        return ResponseEntity.created(URI.create("/api/applications/" + app.id())).eTag(Long.toString(app.version())).body(app);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApplicationView> update(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String version,
            @Valid @RequestBody ApplicationRequest input) {
        ApplicationView app = service.update(user.id(), id, version, input);
        return ResponseEntity.ok().eTag(Long.toString(app.version())).body(app);
    }

    @DeleteMapping("/{id}")
    public ApplicationView delete(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id,
                                  @RequestHeader(value = "If-Match", required = false) String version) {
        return service.delete(user.id(), id, version);
    }

    @PostMapping("/{id}/restore")
    public ApplicationView restore(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id,
                                   @RequestHeader(value = "If-Match", required = false) String version) {
        return service.restore(user.id(), id, version);
    }
}
