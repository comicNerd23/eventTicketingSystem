package com.ticketing.payment.service;

import com.ticketing.payment.client.ChargeResult;
import com.ticketing.payment.client.PaymentGateway;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.dto.PaymentResponse;
import com.ticketing.payment.dto.StripeWebhookRequest;
import com.ticketing.payment.exception.PaymentNotFoundException;
import com.ticketing.payment.exception.PaymentNotFoundForBookingException;
import com.ticketing.payment.exception.UnsupportedWebhookEventException;
import com.ticketing.payment.kafka.producer.PaymentEventPublisher;
import com.ticketing.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository,
                           PaymentGateway paymentGateway,
                           PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void initiatePayment(UUID bookingId, UUID userId, double amountGbp, String stripePaymentMethodId) {
        if (paymentRepository.existsByBookingId(bookingId)) {
            log.info("Payment already exists for booking {} — skipping duplicate payment-initiated", bookingId);
            return;
        }

        ChargeResult charge = paymentGateway.createCharge(bookingId, amountGbp, stripePaymentMethodId);

        Payment payment = new Payment();
        payment.setBookingId(bookingId);
        payment.setUserId(userId);
        payment.setAmountGbp(amountGbp);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStripePaymentIntentId(charge.stripePaymentIntentId());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        log.info("Payment PENDING created: booking={} paymentIntent={}", bookingId, charge.stripePaymentIntentId());
    }

    public PaymentResponse getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
            .map(PaymentResponse::from)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public PaymentResponse getPaymentByBooking(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId)
            .map(PaymentResponse::from)
            .orElseThrow(() -> new PaymentNotFoundForBookingException(bookingId));
    }

    @Transactional
    public void handleWebhook(StripeWebhookRequest request) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(request.getPaymentIntentId())
            .orElseThrow(() -> new PaymentNotFoundException(request.getPaymentIntentId()));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Payment {} already {} — ignoring duplicate webhook", payment.getId(), payment.getStatus());
            return;
        }

        switch (request.getType()) {
            case "payment_intent.succeeded" -> {
                payment.setStatus(PaymentStatus.SUCCEEDED);
                payment.setUpdatedAt(Instant.now());
                paymentRepository.save(payment);
                eventPublisher.publishPaymentCompleted(payment);
                log.info("Payment SUCCEEDED: booking={}", payment.getBookingId());
            }
            case "payment_intent.payment_failed" -> {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(request.getFailureMessage() != null ? request.getFailureMessage() : "Payment failed");
                payment.setUpdatedAt(Instant.now());
                paymentRepository.save(payment);
                eventPublisher.publishPaymentFailed(payment);
                log.info("Payment FAILED: booking={}", payment.getBookingId());
            }
            default -> throw new UnsupportedWebhookEventException(request.getType());
        }
    }
}
