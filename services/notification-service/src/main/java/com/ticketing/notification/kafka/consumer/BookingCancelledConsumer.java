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
public class BookingCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingCancelledConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public BookingCancelledConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "booking-cancelled", groupId = "notification-service-consumer-group")
    public void handleBookingCancelled(String message) {
        log.debug("Received booking-cancelled: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String userEmail = payload.hasNonNull("userEmail") ? payload.get("userEmail").asText() : "demo@ticketing.com";
            UUID seatId = UUID.fromString(payload.get("seatId").asText());
            UUID eventId = UUID.fromString(payload.get("eventId").asText());
            Double amountGbp = payload.hasNonNull("amountGbp") ? payload.get("amountGbp").asDouble() : null;

            notificationService.recordBookingCancelled(bookingId, userId, userEmail, seatId, eventId, amountGbp);
        } catch (Exception e) {
            log.error("Failed to process booking-cancelled: {}", e.getMessage(), e);
        }
    }
}
