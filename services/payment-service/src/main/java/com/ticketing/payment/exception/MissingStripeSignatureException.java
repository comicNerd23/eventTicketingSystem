package com.ticketing.payment.exception;

public class MissingStripeSignatureException extends RuntimeException {

    public MissingStripeSignatureException() {
        super("Missing or blank Stripe-Signature header");
    }
}
