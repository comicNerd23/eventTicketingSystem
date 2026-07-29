package com.ticketing.waitlist.kafka.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.waitlist.service.WaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SeatAvailabilityConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityConsumer.class);

    private final WaitlistService waitlistService;
    private final ObjectMapper objectMapper;

    public SeatAvailabilityConsumer(WaitlistService waitlistService, ObjectMapper objectMapper) {
        this.waitlistService = waitlistService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "seat-released", groupId = "waitlist-service-group")
    public void handleSeatReleased(String message) {
        promoteFromEventPayload("seat-released", message);
    }

    @KafkaListener(topics = "seat-hold-expired", groupId = "waitlist-service-group")
    public void handleSeatHoldExpired(String message) {
        promoteFromEventPayload("seat-hold-expired", message);
    }

    @KafkaListener(topics = "booking-cancelled", groupId = "waitlist-service-group")
    public void handleBookingCancelled(String message) {
        promoteFromEventPayload("booking-cancelled", message);
    }

    private void promoteFromEventPayload(String topic, String message) {
        log.debug("Received {}: {}", topic, message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID eventId = UUID.fromString(payload.get("eventId").asText());
            waitlistService.promoteNextForEvent(eventId);
        } catch (Exception e) {
            log.error("Failed to process {}: {}", topic, e.getMessage(), e);
        }
    }
}
