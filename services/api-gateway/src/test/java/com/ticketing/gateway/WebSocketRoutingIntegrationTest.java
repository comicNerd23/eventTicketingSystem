package com.ticketing.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the gateway actually proxies a WebSocket upgrade end-to-end: a Reactor Netty
 * stub stands in for booking-service's seat-status socket, and a real WebSocket client
 * connects through the gateway and must receive the stub's pushed message — a real
 * upgraded connection, not just a route config that parses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketRoutingIntegrationTest {

    private static DisposableServer bookingWsStub;

    @DynamicPropertySource
    static void stubProperties(DynamicPropertyRegistry registry) {
        bookingWsStub = HttpServer.create()
            .host("localhost")
            .port(0)
            .route(routes -> routes.ws("/bookings/ws/events/{eventId}/seats",
                (in, out) -> out.sendString(Mono.just("hello from booking-service ws"))))
            .bindNow();

        registry.add("booking.service.ws-base-url", () -> "ws://localhost:" + bookingWsStub.port());
        // The existing REST /bookings/** route also needs a resolvable base-url for the
        // gateway context to start — this test only exercises the ws route, so pointing
        // it at the same stub host is harmless (no REST call is made against it here).
        registry.add("booking.service.base-url", () -> "http://localhost:" + bookingWsStub.port());
    }

    @AfterAll
    static void stopStub() {
        bookingWsStub.disposeNow();
    }

    @LocalServerPort
    int gatewayPort;

    @Test
    void bookingsWsPath_routesToBookingServiceWebSocket() {
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        AtomicReference<String> received = new AtomicReference<>();

        URI uri = URI.create(
            "ws://localhost:" + gatewayPort + "/bookings/ws/events/11111111-1111-1111-1111-111111111111/seats");

        client.execute(uri, session -> session.receive()
                .next()
                .doOnNext(message -> received.set(message.getPayloadAsText()))
                .then())
            .block(Duration.ofSeconds(5));

        assertThat(received.get()).isEqualTo("hello from booking-service ws");
    }
}
