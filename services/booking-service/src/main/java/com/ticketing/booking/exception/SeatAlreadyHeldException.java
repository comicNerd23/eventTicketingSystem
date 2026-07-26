package com.ticketing.booking.exception;

import java.util.UUID;

public class SeatAlreadyHeldException extends RuntimeException {
    public SeatAlreadyHeldException(UUID seatId) {
        super("Seat " + seatId + " is already held or booked");
    }
}
