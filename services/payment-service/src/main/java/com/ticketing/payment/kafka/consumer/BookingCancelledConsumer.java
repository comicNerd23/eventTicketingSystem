package com.ticketing.payment.kafka.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BookingCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingCancelledConsumer.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public BookingCancelledConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "booking-cancelled", groupId = "payment-service-group")
    public void handleBookingCancelled(String message) {
        log.debug("Received booking-cancelled: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            paymentService.refundForCancelledBooking(bookingId);
        } catch (Exception e) {
            log.error("Failed to process booking-cancelled: {}", e.getMessage(), e);
        }
    }
}
