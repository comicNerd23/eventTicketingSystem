package com.ticketing.event.exception;

import java.util.UUID;

public class BookingServiceUnavailableException extends RuntimeException {
    public BookingServiceUnavailableException(UUID eventId, Throwable cause) {
        super("booking-service unavailable while fetching active seats for event: " + eventId, cause);
    }
}
