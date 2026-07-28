package com.ticketing.payment.dto;

import jakarta.validation.constraints.NotBlank;

public class StripeWebhookRequest {

    @NotBlank
    private String type;

    @NotBlank
    private String paymentIntentId;

    private String failureMessage;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
