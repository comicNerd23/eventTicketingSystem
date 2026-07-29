package com.ticketing.notification.integration;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.notification.client.NotificationSender;
import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.dto.NotificationResponse;
import com.ticketing.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class NotificationSagaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired TestRestTemplate restTemplate;
    @Autowired NotificationRepository notificationRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    // SpyBean lets the real stub implementation run while allowing verification it was invoked
    @MockitoSpyBean NotificationSender notificationSender;

    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId = UUID.randomUUID();
        notificationRepository.deleteAll();
    }

    @Test
    void ticketIssued_recordsSentNotification_retrievableViaGetEndpoint() throws Exception {
        kafkaTemplate.send("ticket-issued", bookingId.toString(), ticketIssuedEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)).isPresent());

        Notification notification = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getUserEmail()).isEqualTo("user@example.com");

        ResponseEntity<NotificationResponse> resp = restTemplate.getForEntity(
            "/notifications/bookings/{bookingId}", NotificationResponse.class, bookingId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(NotificationStatus.SENT);

        then(notificationSender).should().send(anyString(), anyString(), anyString());
    }

    @Test
    void seatHoldExpired_recordsSentNotification_retrievableViaGetEndpoint() throws Exception {
        kafkaTemplate.send("seat-hold-expired", bookingId.toString(), seatHoldExpiredEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)).isPresent());

        Notification notification = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getType()).isEqualTo(com.ticketing.notification.domain.NotificationType.SEAT_HOLD_EXPIRED);

        ResponseEntity<NotificationResponse> resp = restTemplate.getForEntity(
            "/notifications/bookings/{bookingId}", NotificationResponse.class, bookingId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bookingCancelled_recordsSentNotification_retrievableViaGetEndpoint() throws Exception {
        kafkaTemplate.send("booking-cancelled", bookingId.toString(), bookingCancelledEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)).isPresent());

        Notification notification = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getType()).isEqualTo(com.ticketing.notification.domain.NotificationType.BOOKING_CANCELLED);

        ResponseEntity<NotificationResponse> resp = restTemplate.getForEntity(
            "/notifications/bookings/{bookingId}", NotificationResponse.class, bookingId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void waitlistPromoted_recordsSentNotification_retrievableViaGetEndpoint() throws Exception {
        UUID waitlistEntryId = UUID.randomUUID();
        kafkaTemplate.send("waitlist-promoted", userId.toString(), waitlistPromotedEvent(waitlistEntryId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(notificationRepository.findByWaitlistEntryId(waitlistEntryId)).isPresent());

        Notification notification = notificationRepository.findByWaitlistEntryId(waitlistEntryId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getBookingId()).isNull();

        ResponseEntity<NotificationResponse> resp = restTemplate.getForEntity(
            "/notifications/waitlist-entries/{waitlistEntryId}", NotificationResponse.class, waitlistEntryId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String seatHoldExpiredEvent(UUID bookingId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seatId", UUID.randomUUID().toString());
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("bookingId", bookingId.toString());
        payload.put("userId", userId.toString());
        payload.put("userEmail", "user@example.com");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "seat-hold-expired");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private String bookingCancelledEvent(UUID bookingId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("userId", userId.toString());
        payload.put("userEmail", "user@example.com");
        payload.put("seatId", UUID.randomUUID().toString());
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("amountGbp", 89.5);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "booking-cancelled");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private String waitlistPromotedEvent(UUID waitlistEntryId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waitlistEntryId", waitlistEntryId.toString());
        payload.put("userId", userId.toString());
        payload.put("userEmail", "user@example.com");
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventTitle", "Test Event");
        payload.put("offerExpiresAt", java.time.Instant.now().plusSeconds(900).toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "waitlist-promoted");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private String ticketIssuedEvent(UUID bookingId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("userId", userId.toString());
        payload.put("userEmail", "user@example.com");
        payload.put("ticketReference", "TKT-TEST-1");
        payload.put("eventTitle", "Test Event");
        payload.put("seatLabel", "A1");
        payload.put("startsAt", "2026-08-01T19:00:00Z");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "ticket-issued");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }
}
