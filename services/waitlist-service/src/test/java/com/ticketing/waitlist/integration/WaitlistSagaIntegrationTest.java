package com.ticketing.waitlist.integration;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.waitlist.client.EventInfo;
import com.ticketing.waitlist.client.EventServiceClient;
import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;
import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistEntryResponse;
import com.ticketing.waitlist.kafka.producer.WaitlistEventPublisher;
import com.ticketing.waitlist.repository.WaitlistEntryRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class WaitlistSagaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired TestRestTemplate restTemplate;
    @Autowired WaitlistEntryRepository waitlistEntryRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean WaitlistEventPublisher eventPublisher;
    @MockitoBean EventServiceClient eventServiceClient;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000099";
    private UUID eventId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        waitlistEntryRepository.deleteAll();
        given(eventServiceClient.getEvent(any()))
            .willAnswer(inv -> new EventInfo(inv.getArgument(0), "Test Event"));
    }

    @Test
    void bookingCancelled_promotesOldestWaitingEntry() throws Exception {
        JoinWaitlistRequest req = new JoinWaitlistRequest();
        req.setEventId(eventId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", USER_ID);

        ResponseEntity<WaitlistEntryResponse> joinResp = restTemplate.postForEntity(
            "/waitlist", new HttpEntity<>(req, headers), WaitlistEntryResponse.class);
        assertThat(joinResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID entryId = joinResp.getBody().getId();

        kafkaTemplate.send("booking-cancelled", UUID.randomUUID().toString(), bookingCancelledEvent(eventId));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            WaitlistEntry entry = waitlistEntryRepository.findById(entryId).orElseThrow();
            assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(entry.getOfferExpiresAt()).isNotNull();
        });

        ResponseEntity<WaitlistEntryResponse> getResp = restTemplate.getForEntity(
            "/waitlist/{entryId}", WaitlistEntryResponse.class, entryId);
        assertThat(getResp.getBody().getStatus()).isEqualTo(WaitlistStatus.PROMOTED);

        then(eventPublisher).should().publishWaitlistPromoted(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String bookingCancelledEvent(UUID eventId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", UUID.randomUUID().toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("seatId", UUID.randomUUID().toString());
        payload.put("eventId", eventId.toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "booking-cancelled");
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }
}
