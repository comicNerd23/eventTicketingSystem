package com.ticketing.notification.exception;

import java.util.UUID;

public class NotificationNotFoundForBookingException extends RuntimeException {

    public NotificationNotFoundForBookingException(UUID bookingId) {
        super("Notification not found for bookingId: " + bookingId);
    }
}
