package com.campus.tracker.reminder;

import com.campus.tracker.auth.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.campus.tracker.reminder.ReminderDtos.*;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final ReminderService service;
    public ReminderController(ReminderService service) { this.service = service; }
    @GetMapping public Overview overview(@AuthenticationPrincipal CampusPrincipal user) { return service.overview(user.id()); }
    @PutMapping("/preferences") public Overview preferences(@AuthenticationPrincipal CampusPrincipal user,
        @RequestHeader(value="If-Match",required=false) String version, @Valid @RequestBody Preferences input) {
        return service.savePreferences(user.id(),version,input);
    }
    @PostMapping("/{id}/snooze") public ReminderView snooze(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id,
        @RequestHeader(value="If-Match",required=false) String version, @Valid @RequestBody Snooze input) {
        return service.snooze(user.id(),id,version,input.minutes());
    }
    @PostMapping("/{id}/dismiss") public ReminderView dismiss(@AuthenticationPrincipal CampusPrincipal user, @PathVariable UUID id,
        @RequestHeader(value="If-Match",required=false) String version) { return service.dismiss(user.id(),id,version); }
}
