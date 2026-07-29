package com.ticketing.booking.controller;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.exception.BookingNotCancellableException;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.exception.BookingNotHeldException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import com.ticketing.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean BookingService bookingService;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // ── POST /bookings/hold ───────────────────────────────────────────────────

    @Test
    void holdSeat_validRequest_returns201WithHeldBooking() throws Exception {
        given(bookingService.holdSeat(any(), any()))
            .willReturn(BookingResponse.from(aBooking(UUID.randomUUID(), BookingStatus.HELD)));

        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(UUID.randomUUID());
        req.setSeatId(UUID.randomUUID());
        req.setSeatLabel("A1");

        mvc.perform(post("/bookings/hold")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("HELD"))
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.holdExpiresAt").exists());
    }

    @Test
    void holdSeat_missingRequiredSeatId_returns400WithValidationMessage() throws Exception {
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

        mvc.perform(post("/bookings/hold")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("seatId")));
    }

    @Test
    void holdSeat_whenSeatAlreadyHeld_returns409WithMessage() throws Exception {
        UUID seatId = UUID.randomUUID();
        given(bookingService.holdSeat(any(), any()))
            .willThrow(new SeatAlreadyHeldException(seatId));

        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(UUID.randomUUID());
        req.setSeatId(seatId);

        mvc.perform(post("/bookings/hold")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message").value(containsString(seatId.toString())));
    }

    // ── POST /bookings/{id}/confirm ───────────────────────────────────────────

    @Test
    void confirmBooking_validRequest_returns200WithPaymentPending() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.confirmBooking(eq(bookingId), any(), any()))
            .willReturn(BookingResponse.from(aBooking(bookingId, BookingStatus.PAYMENT_PENDING)));

        ConfirmBookingRequest req = new ConfirmBookingRequest();
        req.setStripePaymentMethodId("pm_test_4242");

        mvc.perform(post("/bookings/{id}/confirm", bookingId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
    }

    @Test
    void confirmBooking_whenBookingNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.confirmBooking(eq(bookingId), any(), any()))
            .willThrow(new BookingNotFoundException(bookingId));

        ConfirmBookingRequest req = new ConfirmBookingRequest();
        req.setStripePaymentMethodId("pm_test_4242");

        mvc.perform(post("/bookings/{id}/confirm", bookingId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void confirmBooking_whenAlreadyConfirmed_returns409WithCurrentStatus() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.confirmBooking(eq(bookingId), any(), any()))
            .willThrow(new BookingNotHeldException(bookingId, BookingStatus.CONFIRMED));

        ConfirmBookingRequest req = new ConfirmBookingRequest();
        req.setStripePaymentMethodId("pm_test_4242");

        mvc.perform(post("/bookings/{id}/confirm", bookingId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("CONFIRMED")));
    }

    @Test
    void confirmBooking_missingStripePaymentMethodId_returns400() throws Exception {
        UUID bookingId = UUID.randomUUID();

        mvc.perform(post("/bookings/{id}/confirm", bookingId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // ── POST /bookings/{id}/cancel ────────────────────────────────────────────

    @Test
    void cancelBooking_whenHeld_returns200WithCancelledStatus() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.cancelBooking(eq(bookingId), any()))
            .willReturn(BookingResponse.from(aBooking(bookingId, BookingStatus.CANCELLED)));

        mvc.perform(post("/bookings/{id}/cancel", bookingId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelBooking_whenNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.cancelBooking(eq(bookingId), any()))
            .willThrow(new BookingNotFoundException(bookingId));

        mvc.perform(post("/bookings/{id}/cancel", bookingId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void cancelBooking_whenNotCancellable_returns409() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.cancelBooking(eq(bookingId), any()))
            .willThrow(new BookingNotCancellableException(bookingId, BookingStatus.PAYMENT_PENDING));

        mvc.perform(post("/bookings/{id}/cancel", bookingId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("PAYMENT_PENDING")));
    }

    // ── GET /bookings/{id} ────────────────────────────────────────────────────

    @Test
    void getBooking_whenConfirmed_returns200WithTicketReference() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, BookingStatus.CONFIRMED);
        booking.setTicketReference("TKT-2026-ABCD");
        booking.setConfirmedAt(Instant.now());
        given(bookingService.getBooking(bookingId)).willReturn(BookingResponse.from(booking));

        mvc.perform(get("/bookings/{id}", bookingId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.ticketReference").value("TKT-2026-ABCD"))
            .andExpect(jsonPath("$.confirmedAt").exists());
    }

    @Test
    void getBooking_whenNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(bookingService.getBooking(bookingId))
            .willThrow(new BookingNotFoundException(bookingId));

        mvc.perform(get("/bookings/{id}", bookingId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Booking aBooking(UUID id, BookingStatus status) {
        Booking b = new Booking();
        b.setId(id);
        b.setEventId(UUID.randomUUID());
        b.setEventTitle("Test Event");
        b.setSeatId(UUID.randomUUID());
        b.setSeatLabel("A1");
        b.setUserId(UUID.fromString(USER_ID));
        b.setStatus(status);
        b.setTotalAmountGbp(89.5);
        b.setHoldExpiresAt(Instant.now().plusSeconds(600));
        return b;
    }
}
