package com.ticketing.notification.client;

public record SendResult(boolean success, String message) {

    public static SendResult succeeded() {
        return new SendResult(true, null);
    }

    public static SendResult failed(String message) {
        return new SendResult(false, message);
    }
}
