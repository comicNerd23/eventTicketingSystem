package com.ticketing.payment.dto;

import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public class PaymentResponse {

    private UUID id;
    private UUID bookingId;
    private UUID userId;
    private Double amountGbp;
    private PaymentStatus status;
    private String stripePaymentIntentId;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public static PaymentResponse from(Payment p) {
        PaymentResponse r = new PaymentResponse();
        r.id = p.getId();
        r.bookingId = p.getBookingId();
        r.userId = p.getUserId();
        r.amountGbp = p.getAmountGbp();
        r.status = p.getStatus();
        r.stripePaymentIntentId = p.getStripePaymentIntentId();
        r.failureReason = p.getFailureReason();
        r.createdAt = p.getCreatedAt();
        r.updatedAt = p.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getUserId() { return userId; }
    public Double getAmountGbp() { return amountGbp; }
    public PaymentStatus getStatus() { return status; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
