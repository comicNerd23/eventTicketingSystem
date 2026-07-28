package com.ticketing.payment.kafka.producer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publishPaymentCompleted(Payment payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", payment.getBookingId().toString());
        payload.put("paymentId", payment.getId().toString());
        payload.put("stripePaymentIntentId", payment.getStripePaymentIntentId());
        payload.put("amountGbp", payment.getAmountGbp());

        publish("payment-completed", payment.getBookingId().toString(), payload);
    }

    public void publishPaymentFailed(Payment payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", payment.getBookingId().toString());
        payload.put("paymentId", payment.getId().toString());
        payload.put("failureReason", payment.getFailureReason());

        publish("payment-failed", payment.getBookingId().toString(), payload);
    }

    private void publish(String topic, String key, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", topic);
            envelope.put("occurredAt", Instant.now().toString());
            envelope.put("payload", payload);

            String json = objectMapper.writeValueAsString(envelope);
            kafka.send(topic, key, json);
            log.debug("Published {} for key {}", topic, key);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize event for topic " + topic, e);
        }
    }
}
