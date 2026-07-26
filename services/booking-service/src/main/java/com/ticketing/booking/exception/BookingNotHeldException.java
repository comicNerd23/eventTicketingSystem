package com.ticketing.booking.exception;

import com.ticketing.booking.domain.BookingStatus;
import java.util.UUID;

public class BookingNotHeldException extends RuntimeException {
    public BookingNotHeldException(UUID bookingId, BookingStatus actual) {
        super("Booking " + bookingId + " is not in HELD state (current: " + actual + ")");
    }
}
