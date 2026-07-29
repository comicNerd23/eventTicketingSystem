package com.ticketing.notification.client;

public interface NotificationSender {

    SendResult send(String toEmail, String subject, String body);
}
