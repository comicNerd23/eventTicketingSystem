package com.ticketing.booking.kafka.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.booking.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultConsumer.class);

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public PaymentResultConsumer(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment-completed", groupId = "booking-service-group")
    public void handlePaymentCompleted(String message) {
        log.debug("Received payment-completed: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            String stripePaymentIntentId = payload.get("stripePaymentIntentId").asText();
            bookingService.handlePaymentCompleted(bookingId, stripePaymentIntentId);
        } catch (Exception e) {
            log.error("Failed to process payment-completed: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "payment-failed", groupId = "booking-service-group")
    public void handlePaymentFailed(String message) {
        log.debug("Received payment-failed: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            String failureReason = payload.get("failureReason").asText();
            bookingService.handlePaymentFailed(bookingId, failureReason);
        } catch (Exception e) {
            log.error("Failed to process payment-failed: {}", e.getMessage(), e);
        }
    }
}
