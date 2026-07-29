package com.ticketing.waitlist.exception;

import java.util.UUID;

public class EventServiceUnavailableException extends RuntimeException {
    public EventServiceUnavailableException(UUID eventId, Throwable cause) {
        super("event-service unavailable while fetching event: " + eventId, cause);
    }
}
