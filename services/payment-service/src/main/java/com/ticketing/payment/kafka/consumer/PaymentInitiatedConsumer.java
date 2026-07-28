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
public class PaymentInitiatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiatedConsumer.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentInitiatedConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment-initiated", groupId = "payment-service-consumer-group")
    public void handlePaymentInitiated(String message) {
        log.debug("Received payment-initiated: {}", message);
        try {
            JsonNode payload = objectMapper.readTree(message).get("payload");
            UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            double amountGbp = payload.get("amountGbp").asDouble();
            String stripePaymentMethodId = payload.get("stripePaymentMethodId").asText();
            paymentService.initiatePayment(bookingId, userId, amountGbp, stripePaymentMethodId);
        } catch (Exception e) {
            log.error("Failed to process payment-initiated: {}", e.getMessage(), e);
        }
    }
}
