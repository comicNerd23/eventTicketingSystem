package com.ticketing.waitlist.dto;

import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;

import java.time.Instant;
import java.util.UUID;

public class WaitlistEntryResponse {

    private UUID id;
    private UUID eventId;
    private String eventTitle;
    private UUID userId;
    private long position;
    private WaitlistStatus status;
    private Instant promotedAt;
    private Instant offerExpiresAt;
    private Instant joinedAt;

    public static WaitlistEntryResponse from(WaitlistEntry e, long position) {
        WaitlistEntryResponse r = new WaitlistEntryResponse();
        r.id = e.getId();
        r.eventId = e.getEventId();
        r.eventTitle = e.getEventTitle();
        r.userId = e.getUserId();
        r.position = position;
        r.status = e.getStatus();
        r.promotedAt = e.getPromotedAt();
        r.offerExpiresAt = e.getOfferExpiresAt();
        r.joinedAt = e.getJoinedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventTitle() { return eventTitle; }
    public UUID getUserId() { return userId; }
    public long getPosition() { return position; }
    public WaitlistStatus getStatus() { return status; }
    public Instant getPromotedAt() { return promotedAt; }
    public Instant getOfferExpiresAt() { return offerExpiresAt; }
    public Instant getJoinedAt() { return joinedAt; }
}
