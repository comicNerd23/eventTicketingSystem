package com.ticketing.booking.exception;

import com.ticketing.booking.domain.BookingStatus;
import java.util.UUID;

public class BookingNotCancellableException extends RuntimeException {
    public BookingNotCancellableException(UUID bookingId, BookingStatus actual) {
        super("Booking " + bookingId + " cannot be cancelled from its current state (current: " + actual + ")");
    }
}
