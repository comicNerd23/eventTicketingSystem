package com.ticketing.booking.controller;

import com.ticketing.booking.dto.ActiveSeatResponse;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/hold")
    public ResponseEntity<BookingResponse> holdSeat(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @Valid @RequestBody HoldSeatRequest request) {
        return ResponseEntity.status(201).body(bookingService.holdSeat(userId, request));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.getBooking(bookingId));
    }

    @PostMapping("/{bookingId}/confirm")
    public ResponseEntity<BookingResponse> confirmBooking(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody ConfirmBookingRequest request) {
        return ResponseEntity.ok(bookingService.confirmBooking(bookingId, userId, request));
    }

    @GetMapping("/events/{eventId}/active-seats")
    public ResponseEntity<List<ActiveSeatResponse>> getActiveSeatsForEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(bookingService.getActiveSeatsForEvent(eventId));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID userId,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId, userId));
    }
}
