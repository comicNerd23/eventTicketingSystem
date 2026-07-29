package com.ticketing.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves real HTTP forwarding through the gateway: a JDK HttpServer stands in for each
 * downstream service, bound to a random port wired in via a route's {@code *.base-url}
 * property. A request through the gateway must actually reach the stub and its response
 * must come back through — a real network hop, not a mocked route table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RoutingIntegrationTest {

    private static HttpServer eventStub;
    private static HttpServer bookingStub;
    private static HttpServer paymentStub;
    private static HttpServer notificationStub;
    private static HttpServer waitlistStub;

    @DynamicPropertySource
    static void stubProperties(DynamicPropertyRegistry registry) throws IOException {
        eventStub = startStub("event-service");
        bookingStub = startStub("booking-service");
        paymentStub = startStub("payment-service");
        notificationStub = startStub("notification-service");
        waitlistStub = startStub("waitlist-service");

        registry.add("event.service.base-url", () -> "http://localhost:" + eventStub.getAddress().getPort());
        registry.add("booking.service.base-url", () -> "http://localhost:" + bookingStub.getAddress().getPort());
        registry.add("payment.service.base-url", () -> "http://localhost:" + paymentStub.getAddress().getPort());
        registry.add("notification.service.base-url", () -> "http://localhost:" + notificationStub.getAddress().getPort());
        registry.add("waitlist.service.base-url", () -> "http://localhost:" + waitlistStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStubs() {
        eventStub.stop(0);
        bookingStub.stop(0);
        paymentStub.stop(0);
        notificationStub.stop(0);
        waitlistStub.stop(0);
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void eventsPath_routesToEventService() {
        webTestClient.get().uri("/events/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from event-service");
    }

    @Test
    void venuesPath_routesToEventService() {
        webTestClient.get().uri("/venues/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from event-service");
    }

    @Test
    void bookingsPath_routesToBookingService() {
        webTestClient.get().uri("/bookings/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from booking-service");
    }

    @Test
    void paymentsPath_routesToPaymentService() {
        webTestClient.get().uri("/payments/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from payment-service");
    }

    @Test
    void notificationsPath_routesToNotificationService() {
        webTestClient.get().uri("/notifications/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from notification-service");
    }

    @Test
    void waitlistPath_routesToWaitlistService() {
        webTestClient.get().uri("/waitlist/00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from waitlist-service");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static HttpServer startStub(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("hello from " + name).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        assertThat(server.getAddress().getPort()).isGreaterThan(0);
        return server;
    }
}
