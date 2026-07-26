package com.ticketing.payment.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PaymentSimulatorConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulatorConsumer.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public PaymentSimulatorConsumer(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @KafkaListener(topics = "payment-initiated", groupId = "payment-service-group")
    public void handlePaymentInitiated(String message) {
        log.info("[SIMULATOR] Received payment-initiated");
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode payload = root.get("payload");

            String bookingId = payload.get("bookingId").asText();
            double amountGbp = payload.has("amountGbp") ? payload.get("amountGbp").asDouble() : 75.0;

            // Simulate a short processing delay
            Thread.sleep(600);

            publishPaymentCompleted(bookingId, amountGbp);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[SIMULATOR] Failed to process payment-initiated: {}", e.getMessage(), e);
        }
    }

    private void publishPaymentCompleted(String bookingId, double amountGbp) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("stripePaymentIntentId", "pi_demo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        payload.put("amountGbp", amountGbp);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "payment-completed");
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);

        String json = objectMapper.writeValueAsString(envelope);
        kafka.send("payment-completed", bookingId, json);
        log.info("[SIMULATOR] Published payment-completed for booking {}", bookingId);
    }
}
