package com.ticketing.booking.kafka.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public BookingEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publishPaymentInitiated(Booking booking, String stripePaymentMethodId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId().toString());
        payload.put("userId", booking.getUserId().toString());
        payload.put("amountGbp", booking.getTotalAmountGbp());
        payload.put("stripePaymentMethodId", stripePaymentMethodId);
        payload.put("eventId", booking.getEventId().toString());
        payload.put("seatId", booking.getSeatId().toString());

        publish("payment-initiated", booking.getId().toString(), payload);
    }

    public void publishTicketIssued(Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId().toString());
        payload.put("userId", booking.getUserId().toString());
        payload.put("userEmail", "demo@ticketing.com");
        payload.put("ticketReference", booking.getTicketReference());
        payload.put("eventTitle", booking.getEventTitle());
        payload.put("seatLabel", booking.getSeatLabel());
        payload.put("startsAt", Instant.now().plus(Duration.ofDays(30)).toString());

        publish("ticket-issued", booking.getId().toString(), payload);
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
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event for topic " + topic, e);
        }
    }
}
