package com.ticketing.notification.kafka.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class WaitlistPromotedConsumer {

    private static final Logger log = LoggerFactory.getLogger(WaitlistPromotedConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public WaitlistPromotedConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "waitlist-promoted", groupId = "notification-service-consumer-group")
    public void handleWaitlistPromoted(String message) {
        log.debug("Received waitlist-promoted: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID waitlistEntryId = UUID.fromString(payload.get("waitlistEntryId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String userEmail = payload.get("userEmail").asText();
            UUID eventId = UUID.fromString(payload.get("eventId").asText());
            String eventTitle = payload.get("eventTitle").asText();
            Instant offerExpiresAt = Instant.parse(payload.get("offerExpiresAt").asText());

            notificationService.recordWaitlistPromoted(waitlistEntryId, userId, userEmail, eventId, eventTitle, offerExpiresAt);
        } catch (Exception e) {
            log.error("Failed to process waitlist-promoted: {}", e.getMessage(), e);
        }
    }
}
