package com.campus.tracker.export;

import com.campus.tracker.auth.CampusPrincipal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.campus.tracker.export.ExportDtos.*;

@RestController
@RequestMapping("/api/v2/exports")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "campus.export.enabled", havingValue = "true", matchIfMissing = true)
public class ExportController {
    private final ExportService service;
    public ExportController(ExportService service) { this.service = service; }
    @PostMapping public ResponseEntity<ExportView> create(@AuthenticationPrincipal CampusPrincipal user, @RequestBody(required = false) ExportRequest input) {
        return ResponseEntity.accepted().body(service.create(user.id()));
    }
    @GetMapping("/{id}") public ExportView get(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id) { return service.get(user.id(), id); }
    @DeleteMapping("/{id}") public ExportView cancel(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id) { return service.cancel(user.id(), id); }
    @GetMapping("/{id}/download") public ResponseEntity<org.springframework.core.io.InputStreamResource> download(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id) {
        var download = service.download(user.id(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).contentLength(download.bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"campus-applications.csv\"").body(download.resource());
    }
}
