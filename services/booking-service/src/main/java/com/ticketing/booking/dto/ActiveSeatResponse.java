package com.ticketing.booking.dto;

import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;

import java.util.UUID;

public class ActiveSeatResponse {

    private UUID seatId;
    private BookingStatus status;

    public static ActiveSeatResponse from(Booking b) {
        ActiveSeatResponse r = new ActiveSeatResponse();
        r.seatId = b.getSeatId();
        r.status = b.getStatus();
        return r;
    }

    public UUID getSeatId() { return seatId; }
    public BookingStatus getStatus() { return status; }
}
