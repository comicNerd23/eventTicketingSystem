package com.ticketing.event.exception;

import java.util.List;
import java.util.UUID;

public class BookingServiceUnavailableException extends RuntimeException {
    public BookingServiceUnavailableException(UUID eventId, Throwable cause) {
        super("booking-service unavailable while fetching active seats for event: " + eventId, cause);
    }

    public BookingServiceUnavailableException(List<UUID> eventIds, Throwable cause) {
        super("booking-service unavailable while fetching active seat counts for events: " + eventIds, cause);
    }
}
