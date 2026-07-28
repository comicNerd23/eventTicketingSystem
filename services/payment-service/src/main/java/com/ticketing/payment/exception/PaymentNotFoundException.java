package com.ticketing.payment.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found: " + paymentId);
    }

    public PaymentNotFoundException(String stripePaymentIntentId) {
        super("Payment not found for stripePaymentIntentId: " + stripePaymentIntentId);
    }
}
