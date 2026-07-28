package com.ticketing.payment.controller;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.dto.PaymentResponse;
import com.ticketing.payment.dto.StripeWebhookRequest;
import com.ticketing.payment.exception.PaymentNotFoundException;
import com.ticketing.payment.exception.PaymentNotFoundForBookingException;
import com.ticketing.payment.exception.UnsupportedWebhookEventException;
import com.ticketing.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PaymentService paymentService;

    // ── GET /payments/{paymentId} ────────────────────────────────────────────

    @Test
    void getPayment_whenFound_returns200() throws Exception {
        UUID paymentId = UUID.randomUUID();
        given(paymentService.getPayment(paymentId)).willReturn(PaymentResponse.from(aPayment(paymentId)));

        mvc.perform(get("/payments/{id}", paymentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(paymentId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getPayment_whenNotFound_returns404() throws Exception {
        UUID paymentId = UUID.randomUUID();
        given(paymentService.getPayment(paymentId)).willThrow(new PaymentNotFoundException(paymentId));

        mvc.perform(get("/payments/{id}", paymentId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /payments/bookings/{bookingId} ───────────────────────────────────

    @Test
    void getPaymentByBooking_whenFound_returns200() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(paymentService.getPaymentByBooking(bookingId)).willReturn(PaymentResponse.from(aPayment(UUID.randomUUID())));

        mvc.perform(get("/payments/bookings/{bookingId}", bookingId))
            .andExpect(status().isOk());
    }

    @Test
    void getPaymentByBooking_whenNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(paymentService.getPaymentByBooking(bookingId))
            .willThrow(new PaymentNotFoundForBookingException(bookingId));

        mvc.perform(get("/payments/bookings/{bookingId}", bookingId))
            .andExpect(status().isNotFound());
    }

    // ── POST /payments/webhook ────────────────────────────────────────────────

    @Test
    void handleWebhook_validRequestWithSignature_returns200() throws Exception {
        mvc.perform(post("/payments/webhook")
                .header("Stripe-Signature", "t=demo,v1=stub_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(webhookRequest("payment_intent.succeeded", "pi_test_1", null))))
            .andExpect(status().isOk());

        then(paymentService).should().handleWebhook(any());
    }

    @Test
    void handleWebhook_missingSignatureHeader_returns400() throws Exception {
        mvc.perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(webhookRequest("payment_intent.succeeded", "pi_test_1", null))))
            .andExpect(status().isBadRequest());

        then(paymentService).should(never()).handleWebhook(any());
    }

    @Test
    void handleWebhook_unsupportedType_returns400() throws Exception {
        willThrow(new UnsupportedWebhookEventException("charge.refunded"))
            .given(paymentService).handleWebhook(any());

        mvc.perform(post("/payments/webhook")
                .header("Stripe-Signature", "t=demo,v1=stub_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(webhookRequest("charge.refunded", "pi_test_1", null))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void handleWebhook_missingRequiredFields_returns400() throws Exception {
        mvc.perform(post("/payments/webhook")
                .header("Stripe-Signature", "t=demo,v1=stub_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Payment aPayment(UUID id) {
        Payment p = new Payment();
        p.setId(id);
        p.setBookingId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setAmountGbp(89.5);
        p.setStatus(PaymentStatus.PENDING);
        p.setStripePaymentIntentId("pi_test_stub");
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
