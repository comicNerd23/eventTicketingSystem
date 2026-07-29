package com.ticketing.notification.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notification.sender", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

    @Override
    public SendResult send(String toEmail, String subject, String body) {
        log.info("[STUB] Sending email to {} — subject: \"{}\"\n{}", toEmail, subject, body);
        return SendResult.succeeded();
    }
}
