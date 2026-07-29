package com.ticketing.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class HoldSeatRequest {

    @NotNull
    private UUID eventId;

    @NotNull
    private UUID seatId;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public UUID getSeatId() { return seatId; }
    public void setSeatId(UUID seatId) { this.seatId = seatId; }
}
