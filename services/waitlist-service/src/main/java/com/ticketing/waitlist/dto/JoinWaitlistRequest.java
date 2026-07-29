package com.ticketing.waitlist.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class JoinWaitlistRequest {

    @NotNull
    private UUID eventId;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
}
