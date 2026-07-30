package com.ticketing.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

    private final StripeWebhookSimulator webhookSimulator;

    public StubPaymentGateway(StripeWebhookSimulator webhookSimulator) {
        this.webhookSimulator = webhookSimulator;
    }

    @Override
    public ChargeResult createCharge(UUID bookingId, double amountGbp, String stripePaymentMethodId) {
        String fakeId = "pi_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[STUB] Simulated PaymentIntent for booking {} (£{}) using {} -> {}",
            bookingId, amountGbp, stripePaymentMethodId, fakeId);
        webhookSimulator.simulateWebhookDelivery(fakeId);
        return new ChargeResult(fakeId);
    }

    @Override
    public RefundResult refund(String stripePaymentIntentId, double amountGbp) {
        String fakeId = "re_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[STUB] Simulated Refund for PaymentIntent {} (£{}) -> {}", stripePaymentIntentId, amountGbp, fakeId);
        return new RefundResult(fakeId);
    }
}
