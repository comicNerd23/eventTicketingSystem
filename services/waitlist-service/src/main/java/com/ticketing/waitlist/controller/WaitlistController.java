package com.ticketing.waitlist.controller;

import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistEntryResponse;
import com.ticketing.waitlist.service.WaitlistService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ResponseEntity<WaitlistEntryResponse> join(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @Valid @RequestBody JoinWaitlistRequest request) {
        return ResponseEntity.status(201).body(waitlistService.join(request.getEventId(), userId));
    }

    @GetMapping("/{entryId}")
    public ResponseEntity<WaitlistEntryResponse> getEntry(@PathVariable UUID entryId) {
        return ResponseEntity.ok(waitlistService.getEntry(entryId));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> leave(@PathVariable UUID entryId) {
        waitlistService.leave(entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/me")
    public ResponseEntity<WaitlistEntryResponse> getMyEntryForEvent(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @PathVariable UUID eventId) {
        return ResponseEntity.ok(waitlistService.getMyEntryForEvent(eventId, userId));
    }
}
