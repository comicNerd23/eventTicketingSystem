package com.ticketing.notification.kafka.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SeatHoldExpiredConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiredConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public SeatHoldExpiredConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "seat-hold-expired", groupId = "notification-service-consumer-group")
    public void handleSeatHoldExpired(String message) {
        log.debug("Received seat-hold-expired: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String userEmail = payload.get("userEmail").asText();
            UUID seatId = UUID.fromString(payload.get("seatId").asText());
            UUID eventId = UUID.fromString(payload.get("eventId").asText());

            notificationService.recordSeatHoldExpired(bookingId, userId, userEmail, seatId, eventId);
        } catch (Exception e) {
            log.error("Failed to process seat-hold-expired: {}", e.getMessage(), e);
        }
    }
}
