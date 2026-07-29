package com.ticketing.booking.integration;

import com.ticketing.booking.client.EventInfo;
import com.ticketing.booking.client.EventServiceClient;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.kafka.producer.BookingEventPublisher;
import com.ticketing.booking.repository.BookingRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Verifies the real Redis TTL-expiry mechanism end-to-end: holds a seat with a
 * deliberately short TTL override, then waits for Redis itself (not a manual test
 * publish) to fire the keyspace-expired event that {@code SeatHoldExpiredListener}
 * picks up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "booking.hold.ttl-seconds=2")
@AutoConfigureTestRestTemplate
@Testcontainers
class SeatHoldExpiryIntegrationTest {

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
        .withExposedPorts(6379)
        .withCommand("redis-server", "--notify-keyspace-events", "KEA");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired BookingRepository bookingRepository;

    @MockitoSpyBean BookingEventPublisher eventPublisher;
    @MockitoBean EventServiceClient eventServiceClient;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000099";
    private UUID eventId;
    private UUID seatId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        seatId = UUID.randomUUID();
        bookingRepository.deleteAll();
        given(eventServiceClient.getEvent(any()))
            .willAnswer(inv -> new EventInfo(inv.getArgument(0), "Test Event"));
    }

    @Test
    void heldSeat_whenRedisTtlFires_transitionsToExpiredAndPublishesSeatHoldExpired() {
        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(eventId);
        req.setSeatId(seatId);
        req.setSeatLabel("B7");
        req.setPriceGbp(89.5);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", USER_ID);

        ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
            "/bookings/hold", new HttpEntity<>(req, headers), BookingResponse.class);
        assertThat(holdResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = holdResp.getBody().getId();

        await().atMost(15, SECONDS).untilAsserted(() -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
            assertThat(booking.getExpiredAt()).isNotNull();
        });

        then(eventPublisher).should().publishSeatHoldExpired(any());
    }
}
