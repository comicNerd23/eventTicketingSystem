package com.ticketing.payment.integration;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.dto.PaymentResponse;
import com.ticketing.payment.kafka.producer.PaymentEventPublisher;
import com.ticketing.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PaymentSagaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired TestRestTemplate restTemplate;
    @Autowired PaymentRepository paymentRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    // SpyBean lets the real implementation run (publishes to Kafka for real) while allowing verification
    @MockitoSpyBean PaymentEventPublisher eventPublisher;

    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId = UUID.randomUUID();
        paymentRepository.deleteAll();
    }

    @Test
    void paymentInitiated_thenWebhookSucceeded_endsSucceededAndPublishesPaymentCompleted() throws Exception {
        kafkaTemplate.send("payment-initiated", bookingId.toString(), paymentInitiatedEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(paymentRepository.findByBookingId(bookingId)).isPresent());

        Payment initialPayment = paymentRepository.findByBookingId(bookingId).orElseThrow();
        assertThat(initialPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(initialPayment.getStripePaymentIntentId()).startsWith("pi_stub_");

        ResponseEntity<PaymentResponse> pendingResp = restTemplate.getForEntity(
            "/payments/bookings/{bookingId}", PaymentResponse.class, bookingId);
        assertThat(pendingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String stripePaymentIntentId = pendingResp.getBody().getStripePaymentIntentId();

        ResponseEntity<Void> webhookResp = restTemplate.postForEntity(
            "/payments/webhook",
            webhookEntity("payment_intent.succeeded", stripePaymentIntentId, null),
            Void.class);
        assertThat(webhookResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        });

        then(eventPublisher).should().publishPaymentCompleted(any());
        then(eventPublisher).should(never()).publishPaymentFailed(any());
    }

    @Test
    void paymentInitiated_withNoManualWebhookCall_stillReachesSucceededOnItsOwn() throws Exception {
        // Proves the ADR-011 fix: the stub gateway self-delivers a simulated Stripe webhook
        // ~1s after createCharge, so the saga completes with zero manual webhook calls — the
        // exact scenario that was broken (bookings stuck forever at PAYMENT_PENDING).
        kafkaTemplate.send("payment-initiated", bookingId.toString(), paymentInitiatedEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        });

        then(eventPublisher).should().publishPaymentCompleted(any());
        then(eventPublisher).should(never()).publishPaymentFailed(any());
    }

    @Test
    void paymentInitiated_thenWebhookFailed_endsFailedAndPublishesPaymentFailed() throws Exception {
        kafkaTemplate.send("payment-initiated", bookingId.toString(), paymentInitiatedEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(paymentRepository.findByBookingId(bookingId)).isPresent());

        String stripePaymentIntentId = paymentRepository.findByBookingId(bookingId).orElseThrow()
            .getStripePaymentIntentId();

        ResponseEntity<Void> webhookResp = restTemplate.postForEntity(
            "/payments/webhook",
            webhookEntity("payment_intent.payment_failed", stripePaymentIntentId, "card_declined"),
            Void.class);
        assertThat(webhookResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("card_declined");
        });

        then(eventPublisher).should().publishPaymentFailed(any());
        then(eventPublisher).should(never()).publishPaymentCompleted(any());
    }

    @Test
    void bookingCancelled_afterPaymentSucceeded_refundsAndTransitionsToRefunded() throws Exception {
        kafkaTemplate.send("payment-initiated", bookingId.toString(), paymentInitiatedEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(paymentRepository.findByBookingId(bookingId)).isPresent());

        String stripePaymentIntentId = paymentRepository.findByBookingId(bookingId).orElseThrow()
            .getStripePaymentIntentId();

        ResponseEntity<Void> webhookResp = restTemplate.postForEntity(
            "/payments/webhook",
            webhookEntity("payment_intent.succeeded", stripePaymentIntentId, null),
            Void.class);
        assertThat(webhookResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED));

        kafkaTemplate.send("booking-cancelled", bookingId.toString(), bookingCancelledEvent(bookingId, userId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String paymentInitiatedEvent(UUID bookingId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("userId", userId.toString());
        payload.put("amountGbp", 89.5);
        payload.put("stripePaymentMethodId", "pm_test_4242424242424242");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "payment-initiated");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private String bookingCancelledEvent(UUID bookingId, UUID userId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("userId", userId.toString());
        payload.put("userEmail", "demo@ticketing.com");
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

    private HttpEntity<String> webhookEntity(String type, String paymentIntentId, String failureMessage) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("paymentIntentId", paymentIntentId);
        if (failureMessage != null) {
            body.put("failureMessage", failureMessage);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "t=demo,v1=stub_sig");
        return new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
    }
}
