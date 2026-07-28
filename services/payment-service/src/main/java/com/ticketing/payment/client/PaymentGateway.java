package com.ticketing.payment.client;

import java.util.UUID;

public interface PaymentGateway {

    ChargeResult createCharge(UUID bookingId, double amountGbp, String stripePaymentMethodId);
}
