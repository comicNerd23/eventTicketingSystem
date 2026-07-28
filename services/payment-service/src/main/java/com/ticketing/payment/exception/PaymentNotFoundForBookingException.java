package com.ticketing.payment.exception;

import java.util.UUID;

public class PaymentNotFoundForBookingException extends RuntimeException {

    public PaymentNotFoundForBookingException(UUID bookingId) {
        super("Payment not found for booking: " + bookingId);
    }
}
