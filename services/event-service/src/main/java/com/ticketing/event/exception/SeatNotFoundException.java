package com.ticketing.event.exception;

import java.util.UUID;

public class SeatNotFoundException extends RuntimeException {
    public SeatNotFoundException(UUID eventId, UUID seatId) {
        super("Seat " + seatId + " not found for event " + eventId);
    }
}
