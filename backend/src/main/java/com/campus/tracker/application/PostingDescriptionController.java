package com.campus.tracker.application;

import com.campus.tracker.auth.CampusPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/applications/{id}") @Validated
public class PostingDescriptionController {
    private final ApplicationService applications;
    public PostingDescriptionController(ApplicationService applications) {this.applications=applications;}
    @GetMapping("/posting-description")
    public ResponseEntity<PostingDescriptionService.Current> current(@AuthenticationPrincipal CampusPrincipal user,@PathVariable UUID id) {
        return privateResponse(applications.currentDescription(user.id(),id));
    }
    @GetMapping("/posting-descriptions")
    public ResponseEntity<PostingDescriptionService.History> history(@AuthenticationPrincipal CampusPrincipal user,@PathVariable UUID id,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size) {
        return privateResponse(applications.descriptionHistory(user.id(),id,page,size));
    }
    @GetMapping("/posting-descriptions/{snapshotId}")
    public ResponseEntity<PostingDescriptionService.Snapshot> snapshot(@AuthenticationPrincipal CampusPrincipal user,@PathVariable UUID id,@PathVariable UUID snapshotId) {
        return privateResponse(applications.descriptionSnapshot(user.id(),id,snapshotId));
    }
    private static <T> ResponseEntity<T> privateResponse(T body) {return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
}
