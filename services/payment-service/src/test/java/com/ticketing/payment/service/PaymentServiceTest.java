package com.ticketing.payment.service;

import com.ticketing.payment.client.ChargeResult;
import com.ticketing.payment.client.PaymentGateway;
import com.ticketing.payment.client.RefundResult;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.dto.PaymentResponse;
import com.ticketing.payment.dto.StripeWebhookRequest;
import com.ticketing.payment.exception.PaymentNotFoundException;
import com.ticketing.payment.exception.PaymentNotFoundForBookingException;
import com.ticketing.payment.exception.UnsupportedWebhookEventException;
import com.ticketing.payment.kafka.producer.PaymentEventPublisher;
import com.ticketing.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock PaymentEventPublisher eventPublisher;

    @InjectMocks PaymentService paymentService;

    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    // ── initiatePayment ──────────────────────────────────────────────────────

    @Test
    void initiatePayment_whenNoExistingPayment_createsPendingPaymentViaGateway() {
        given(paymentRepository.existsByBookingId(bookingId)).willReturn(false);
        given(paymentGateway.createCharge(bookingId, 89.5, "pm_test_4242"))
            .willReturn(new ChargeResult("pi_stub_abc123"));

        paymentService.initiatePayment(bookingId, userId, 89.5, "pm_test_4242");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(bookingId);
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getAmountGbp()).isEqualTo(89.5);
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getValue().getStripePaymentIntentId()).isEqualTo("pi_stub_abc123");
    }

    @Test
    void initiatePayment_whenPaymentAlreadyExistsForBooking_isNoOp() {
        given(paymentRepository.existsByBookingId(bookingId)).willReturn(true);

        paymentService.initiatePayment(bookingId, userId, 89.5, "pm_test_4242");

        then(paymentGateway).shouldHaveNoInteractions();
        then(paymentRepository).should(never()).save(any());
    }

    // ── handleWebhook ────────────────────────────────────────────────────────

    @Test
    void handleWebhook_succeeded_transitionsToSucceededAndPublishes() {
        Payment payment = aPayment(bookingId, "pi_test_1", PaymentStatus.PENDING);
        given(paymentRepository.findByStripePaymentIntentId("pi_test_1")).willReturn(Optional.of(payment));

        paymentService.handleWebhook(webhookRequest("payment_intent.succeeded", "pi_test_1", null));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        then(paymentRepository).should().save(payment);
        then(eventPublisher).should().publishPaymentCompleted(payment);
        then(eventPublisher).should(never()).publishPaymentFailed(any());
    }

    @Test
    void handleWebhook_paymentFailed_transitionsToFailedWithReasonAndPublishes() {
        Payment payment = aPayment(bookingId, "pi_test_2", PaymentStatus.PENDING);
        given(paymentRepository.findByStripePaymentIntentId("pi_test_2")).willReturn(Optional.of(payment));

        paymentService.handleWebhook(webhookRequest("payment_intent.payment_failed", "pi_test_2", "card_declined"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("card_declined");
        then(eventPublisher).should().publishPaymentFailed(payment);
        then(eventPublisher).should(never()).publishPaymentCompleted(any());
    }

    @Test
    void handleWebhook_whenPaymentAlreadyTerminal_isIdempotentNoOp() {
        Payment payment = aPayment(bookingId, "pi_test_3", PaymentStatus.SUCCEEDED);
        given(paymentRepository.findByStripePaymentIntentId("pi_test_3")).willReturn(Optional.of(payment));

        paymentService.handleWebhook(webhookRequest("payment_intent.succeeded", "pi_test_3", null));

        then(paymentRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void handleWebhook_whenPaymentIntentUnknown_throwsPaymentNotFound() {
        given(paymentRepository.findByStripePaymentIntentId("pi_unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.handleWebhook(webhookRequest("payment_intent.succeeded", "pi_unknown", null)))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void handleWebhook_whenTypeUnsupported_throwsUnsupportedWebhookEventException() {
        Payment payment = aPayment(bookingId, "pi_test_4", PaymentStatus.PENDING);
        given(paymentRepository.findByStripePaymentIntentId("pi_test_4")).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.handleWebhook(webhookRequest("charge.refunded", "pi_test_4", null)))
            .isInstanceOf(UnsupportedWebhookEventException.class);
    }

    // ── refundForCancelledBooking ────────────────────────────────────────────

    @Test
    void refundForCancelledBooking_whenNoPaymentFound_isNoOp() {
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.empty());

        paymentService.refundForCancelledBooking(bookingId);

        then(paymentGateway).shouldHaveNoInteractions();
        then(paymentRepository).should(never()).save(any());
    }

    @Test
    void refundForCancelledBooking_whenPaymentStillPending_isNoOp() {
        Payment payment = aPayment(bookingId, "pi_test_7", PaymentStatus.PENDING);
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.of(payment));

        paymentService.refundForCancelledBooking(bookingId);

        then(paymentGateway).shouldHaveNoInteractions();
        then(paymentRepository).should(never()).save(any());
    }

    @Test
    void refundForCancelledBooking_whenAlreadyRefunded_isIdempotentNoOp() {
        Payment payment = aPayment(bookingId, "pi_test_8", PaymentStatus.REFUNDED);
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.of(payment));

        paymentService.refundForCancelledBooking(bookingId);

        then(paymentGateway).shouldHaveNoInteractions();
        then(paymentRepository).should(never()).save(any());
    }

    @Test
    void refundForCancelledBooking_whenSucceeded_refundsViaGatewayAndTransitionsToRefunded() {
        Payment payment = aPayment(bookingId, "pi_test_9", PaymentStatus.SUCCEEDED);
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.of(payment));
        given(paymentGateway.refund("pi_test_9", 89.5)).willReturn(new RefundResult("re_stub_xyz"));

        paymentService.refundForCancelledBooking(bookingId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        then(paymentGateway).should().refund("pi_test_9", 89.5);
        then(paymentRepository).should().save(payment);
    }

    // ── getPayment / getPaymentByBooking ─────────────────────────────────────

    @Test
    void getPayment_whenExists_returnsResponse() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = aPayment(bookingId, "pi_test_5", PaymentStatus.PENDING);
        given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPayment(paymentId);

        assertThat(response.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void getPayment_whenNotFound_throwsPaymentNotFoundException() {
        UUID paymentId = UUID.randomUUID();
        given(paymentRepository.findById(paymentId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(paymentId))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void getPaymentByBooking_whenExists_returnsResponse() {
        Payment payment = aPayment(bookingId, "pi_test_6", PaymentStatus.PENDING);
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPaymentByBooking(bookingId);

        assertThat(response.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void getPaymentByBooking_whenNotFound_throwsPaymentNotFoundForBookingException() {
        given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentByBooking(bookingId))
            .isInstanceOf(PaymentNotFoundForBookingException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Payment aPayment(UUID bookingId, String stripePaymentIntentId, PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setBookingId(bookingId);
        p.setUserId(UUID.randomUUID());
        p.setAmountGbp(89.5);
        p.setStatus(status);
        p.setStripePaymentIntentId(stripePaymentIntentId);
        return p;
    }

    private StripeWebhookRequest webhookRequest(String type, String paymentIntentId, String failureMessage) {
        StripeWebhookRequest req = new StripeWebhookRequest();
        req.setType(type);
        req.setPaymentIntentId(paymentIntentId);
        req.setFailureMessage(failureMessage);
        return req;
    }
}
