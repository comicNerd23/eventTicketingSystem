package com.ticketing.notification.exception;

import java.util.UUID;

public class NotificationNotFoundForWaitlistEntryException extends RuntimeException {

    public NotificationNotFoundForWaitlistEntryException(UUID waitlistEntryId) {
        super("Notification not found for waitlistEntryId: " + waitlistEntryId);
    }
}
