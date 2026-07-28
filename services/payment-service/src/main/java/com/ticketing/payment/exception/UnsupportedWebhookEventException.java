package com.ticketing.payment.exception;

public class UnsupportedWebhookEventException extends RuntimeException {

    public UnsupportedWebhookEventException(String type) {
        super("Unsupported webhook event type: " + type);
    }
}
