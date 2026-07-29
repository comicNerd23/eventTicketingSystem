package com.ticketing.waitlist.exception;

import java.util.UUID;

public class AlreadyOnWaitlistException extends RuntimeException {
    public AlreadyOnWaitlistException(UUID eventId, UUID userId) {
        super("User " + userId + " is already on the waitlist for event " + eventId);
    }
}
