package com.ticketing.payment.controller;

import com.ticketing.payment.dto.PaymentResponse;
import com.ticketing.payment.dto.StripeWebhookRequest;
import com.ticketing.payment.exception.MissingStripeSignatureException;
import com.ticketing.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<PaymentResponse> getPaymentByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(paymentService.getPaymentByBooking(bookingId));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @Valid @RequestBody StripeWebhookRequest request) {
        if (signature == null || signature.isBlank()) {
            throw new MissingStripeSignatureException();
        }
        paymentService.handleWebhook(request);
        return ResponseEntity.ok().build();
    }
}
