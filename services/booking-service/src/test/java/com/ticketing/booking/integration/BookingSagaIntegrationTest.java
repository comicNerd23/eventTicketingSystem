package com.ticketing.booking.integration;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.kafka.producer.BookingEventPublisher;
import com.ticketing.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class BookingSagaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired BookingRepository bookingRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    // SpyBean lets the real implementation run while still allowing Mockito verification
    @MockitoSpyBean BookingEventPublisher eventPublisher;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000099";
    private UUID eventId;
    private UUID seatId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        seatId  = UUID.randomUUID();
        bookingRepository.deleteAll();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void fullSaga_holdConfirmPaymentCompleted_bookingEndsConfirmedWithTicket() throws Exception {
        // ── Step 1: Hold the seat ─────────────────────────────────────────────
        ResponseEntity<BookingResponse> holdResp = holdSeat(eventId, seatId);

        assertThat(holdResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(holdResp.getBody()).isNotNull();
        assertThat(holdResp.getBody().getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(holdResp.getBody().getHoldExpiresAt()).isAfter(Instant.now());

        UUID bookingId = holdResp.getBody().getId();

        // ── Step 2: Redis lock must be active ─────────────────────────────────
        assertThat(redisTemplate.hasKey("seat-hold:" + seatId)).isTrue();

        // ── Step 3: Confirm booking (triggers Saga) ───────────────────────────
        ResponseEntity<BookingResponse> confirmResp = confirmBooking(bookingId);

        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResp.getBody().getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);

        // ── Step 4: booking-service must have published payment-initiated ──────
        then(eventPublisher).should().publishPaymentInitiated(any(), any());

        // ── Step 5: Simulate payment-service — publish payment-completed ───────
        kafkaTemplate.send("payment-completed", bookingId.toString(),
            buildPaymentCompletedEvent(bookingId));

        // ── Step 6: Wait for async Kafka consumer to process and confirm ───────
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getTicketReference()).matches("TKT-\\d{4}-[A-F0-9]{4}");
            assertThat(booking.getConfirmedAt()).isNotNull();
        });

        // ── Step 7: booking-service must have published ticket-issued ──────────
        then(eventPublisher).should().publishTicketIssued(any());

        // ── Step 8: Redis lock must be released once seat is booked ───────────
        assertThat(redisTemplate.hasKey("seat-hold:" + seatId)).isFalse();

        // ── Step 9: GET endpoint returns the final confirmed state ─────────────
        ResponseEntity<BookingResponse> finalResp = restTemplate.getForEntity(
            "/bookings/{id}", BookingResponse.class, bookingId);

        assertThat(finalResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalResp.getBody().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(finalResp.getBody().getTicketReference()).startsWith("TKT-");
    }

    // ── Seat protection ───────────────────────────────────────────────────────

    @Test
    void holdSeat_afterSeatAlreadyConfirmed_returns409() throws Exception {
        // Set up a confirmed booking for the seat directly in the DB
        Booking existing = new Booking();
        existing.setEventId(eventId);
        existing.setEventTitle("Some Event");
        existing.setSeatId(seatId);
        existing.setSeatLabel("A1");
        existing.setUserId(UUID.randomUUID());
        existing.setStatus(BookingStatus.CONFIRMED);
        existing.setTotalAmountGbp(50.0);
        existing.setHoldExpiresAt(Instant.now().plusSeconds(600));
        bookingRepository.save(existing);

        ResponseEntity<String> response = holdSeat(eventId, seatId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void confirmBooking_afterAlreadyConfirmed_returns409() throws Exception {
        // Hold and confirm normally first
        UUID bookingId = holdSeat(eventId, seatId).getBody().getId();
        confirmBooking(bookingId);
        kafkaTemplate.send("payment-completed", bookingId.toString(),
            buildPaymentCompletedEvent(bookingId));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED));

        // Attempt to confirm again — must be rejected
        ResponseEntity<String> secondConfirm = restTemplate.postForEntity(
            "/bookings/{id}/confirm", jsonEntity(new ConfirmBookingRequest() {{
                setStripePaymentMethodId("pm_test_second");
            }}), String.class, bookingId);

        assertThat(secondConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<BookingResponse> holdSeat(UUID eventId, UUID seatId) {
        return holdSeat(eventId, seatId, BookingResponse.class);
    }

    private <T> ResponseEntity<T> holdSeat(UUID eventId, UUID seatId, Class<T> responseType) {
        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(eventId);
        req.setSeatId(seatId);
        req.setEventTitle("Coldplay: Music of the Spheres Tour");
        req.setSeatLabel("B7");
        req.setPriceGbp(89.5);
        return restTemplate.postForEntity("/bookings/hold", jsonEntity(req), responseType);
    }

    private ResponseEntity<BookingResponse> confirmBooking(UUID bookingId) {
        ConfirmBookingRequest req = new ConfirmBookingRequest();
        req.setStripePaymentMethodId("pm_test_4242424242424242");
        return restTemplate.postForEntity(
            "/bookings/{id}/confirm", jsonEntity(req), BookingResponse.class, bookingId);
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", USER_ID);
        return new HttpEntity<>(body, headers);
    }

    private String buildPaymentCompletedEvent(UUID bookingId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("stripePaymentIntentId", "pi_demo_test_" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("amountGbp", 89.5);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "payment-completed");
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }
}
