package com.ticketing.waitlist.kafka.producer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ticketing.waitlist.domain.WaitlistEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class WaitlistEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WaitlistEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public WaitlistEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publishWaitlistPromoted(WaitlistEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waitlistEntryId", entry.getId().toString());
        payload.put("userId", entry.getUserId().toString());
        payload.put("userEmail", "demo@ticketing.com");
        payload.put("eventId", entry.getEventId().toString());
        payload.put("eventTitle", entry.getEventTitle());
        payload.put("offerExpiresAt", entry.getOfferExpiresAt().toString());

        publish("waitlist-promoted", entry.getUserId().toString(), payload);
    }

    private void publish(String topic, String key, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", topic);
            envelope.put("occurredAt", java.time.Instant.now().toString());
            envelope.put("payload", payload);

            String json = objectMapper.writeValueAsString(envelope);
            kafka.send(topic, key, json);
            log.debug("Published {} for key {}", topic, key);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize event for topic " + topic, e);
        }
    }
}
