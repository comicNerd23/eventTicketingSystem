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

    @Override
    public ChargeResult createCharge(UUID bookingId, double amountGbp, String stripePaymentMethodId) {
        String fakeId = "pi_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[STUB] Simulated PaymentIntent for booking {} (£{}) using {} -> {}",
            bookingId, amountGbp, stripePaymentMethodId, fakeId);
        return new ChargeResult(fakeId);
    }
}
