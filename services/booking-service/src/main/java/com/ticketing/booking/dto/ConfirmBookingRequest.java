package com.ticketing.booking.dto;

import jakarta.validation.constraints.NotBlank;

public class ConfirmBookingRequest {

    @NotBlank
    private String stripePaymentMethodId;

    public String getStripePaymentMethodId() { return stripePaymentMethodId; }
    public void setStripePaymentMethodId(String stripePaymentMethodId) {
        this.stripePaymentMethodId = stripePaymentMethodId;
    }
}
