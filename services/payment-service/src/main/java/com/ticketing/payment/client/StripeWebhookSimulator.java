package com.ticketing.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Stands in for Stripe itself: a real payment provider confirms a PaymentIntent by calling our
 * webhook endpoint moments later, asynchronously. Split into its own bean (rather than a method
 * on StubPaymentGateway) so Spring's @Async proxy actually intercepts the call — self-invocation
 * within the same bean would silently run synchronously and block the calling thread.
 */
@Component
@ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StripeWebhookSimulator {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookSimulator.class);
    private static final long SIMULATED_STRIPE_DELIVERY_DELAY_MS = 1000;

    private final RestClient.Builder restClientBuilder;
    private final Environment environment;

    public StripeWebhookSimulator(RestClient.Builder restClientBuilder, Environment environment) {
        this.restClientBuilder = restClientBuilder;
        this.environment = environment;
    }

    @Async
    public void simulateWebhookDelivery(String stripePaymentIntentId) {
        try {
            Thread.sleep(SIMULATED_STRIPE_DELIVERY_DELAY_MS);
            // Resolved lazily (not at construction time): the embedded web server hasn't bound
            // its actual port yet when this bean is created, so "local.server.port" — the
            // property Spring Boot publishes once the server has actually started — is only
            // safe to read once a request is genuinely in flight, which is always true by now.
            int port = environment.getProperty("local.server.port", Integer.class,
                environment.getRequiredProperty("server.port", Integer.class));

            restClientBuilder.baseUrl("http://localhost:" + port).build()
                .post()
                .uri("/payments/webhook")
                .header("Stripe-Signature", "t=stub,v1=stub_signature")
                .body(Map.of(
                    "type", "payment_intent.succeeded",
                    "paymentIntentId", stripePaymentIntentId
                ))
                .retrieve()
                .toBodilessEntity();
            log.info("[STUB] Simulated Stripe webhook delivery for PaymentIntent {}", stripePaymentIntentId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[STUB] Failed to simulate Stripe webhook delivery for PaymentIntent {}", stripePaymentIntentId, e);
        }
    }
}
