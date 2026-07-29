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
public class TicketIssuedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuedConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public TicketIssuedConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "ticket-issued", groupId = "notification-service-consumer-group")
    public void handleTicketIssued(String message) {
        log.debug("Received ticket-issued: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String userEmail = payload.get("userEmail").asText();
            String ticketReference = payload.get("ticketReference").asText();
            String eventTitle = payload.get("eventTitle").asText();
            String venueName = payload.hasNonNull("venueName") ? payload.get("venueName").asText() : null;
            String seatLabel = payload.get("seatLabel").asText();
            Instant startsAt = Instant.parse(payload.get("startsAt").asText());

            notificationService.recordTicketIssued(
                bookingId, userId, userEmail, ticketReference, eventTitle, venueName, seatLabel, startsAt);
        } catch (Exception e) {
            log.error("Failed to process ticket-issued: {}", e.getMessage(), e);
        }
    }
}
