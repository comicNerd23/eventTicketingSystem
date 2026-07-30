package com.ticketing.booking.integration;

import com.ticketing.booking.client.EventInfo;
import com.ticketing.booking.client.EventServiceClient;
import com.ticketing.booking.client.SeatInfo;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.HoldSeatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Proves the WebSocket broadcast end-to-end: a real WebSocket client connects to the
 * running server, a real {@code POST /bookings/hold} call is made, and the client must
 * receive the resulting seat-status delta over the socket — not a mocked handler call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class SeatStatusWebSocketIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @MockitoBean EventServiceClient eventServiceClient;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000099";

    @BeforeEach
    void setUp() {
        given(eventServiceClient.getEvent(any()))
            .willAnswer(inv -> new EventInfo(inv.getArgument(0), "Test Event"));
        given(eventServiceClient.getSeat(any(), any()))
            .willAnswer(inv -> new SeatInfo(inv.getArgument(1), "B7", 89.5));
    }

    @Test
    void holdingASeat_broadcastsItsNewStatusOverTheWebSocket() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketHandler clientHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/bookings/ws/events/" + eventId + "/seats");
        WebSocketSession session = client.execute(clientHandler, uri.toString()).get(5, SECONDS);

        try {
            HoldSeatRequest req = new HoldSeatRequest();
            req.setEventId(eventId);
            req.setSeatId(seatId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-Id", USER_ID);

            ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
                "/bookings/hold", new HttpEntity<>(req, headers), BookingResponse.class);
            assertThat(holdResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            String message = received.poll(10, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            assertThat(message).contains("\"seatId\":\"" + seatId + "\"").contains("\"status\":\"HELD\"");
        } finally {
            session.close();
        }
    }
}
