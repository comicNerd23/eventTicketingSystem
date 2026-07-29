package com.ticketing.booking.service;

import com.ticketing.booking.client.EventInfo;
import com.ticketing.booking.client.EventServiceClient;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.exception.BookingNotHeldException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import com.ticketing.booking.kafka.producer.BookingEventPublisher;
import com.ticketing.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final SeatHoldService seatHoldService;
    private final BookingEventPublisher eventPublisher;
    private final EventServiceClient eventServiceClient;

    public BookingService(BookingRepository bookingRepository,
                          SeatHoldService seatHoldService,
                          BookingEventPublisher eventPublisher,
                          EventServiceClient eventServiceClient) {
        this.bookingRepository = bookingRepository;
        this.seatHoldService = seatHoldService;
        this.eventPublisher = eventPublisher;
        this.eventServiceClient = eventServiceClient;
    }

    @Transactional
    public BookingResponse holdSeat(UUID userId, HoldSeatRequest request) {
        // Reject if seat already has an active or confirmed booking in the DB
        if (bookingRepository.existsBySeatIdAndStatusIn(request.getSeatId(),
                List.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.HELD))) {
            throw new SeatAlreadyHeldException(request.getSeatId());
        }

        // Validates the event is real and fetches its title from event-service
        EventInfo event = eventServiceClient.getEvent(request.getEventId());

        boolean acquired = seatHoldService.acquireHold(request.getSeatId());
        if (!acquired) {
            throw new SeatAlreadyHeldException(request.getSeatId());
        }

        Booking booking = new Booking();
        booking.setEventId(event.id());
        booking.setEventTitle(event.title());
        booking.setSeatId(request.getSeatId());
        booking.setSeatLabel(request.getSeatLabel() != null ? request.getSeatLabel() : "A1");
        booking.setUserId(userId);
        booking.setStatus(BookingStatus.HELD);
        booking.setTotalAmountGbp(request.getPriceGbp() != null ? request.getPriceGbp() : 75.00);
        booking.setHoldExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));

        try {
            booking = bookingRepository.save(booking);
            log.info("Seat hold created: booking={} seat={}", booking.getId(), request.getSeatId());
            return BookingResponse.from(booking);
        } catch (Exception e) {
            seatHoldService.releaseHold(request.getSeatId());
            throw e;
        }
    }

    @Transactional
    public BookingResponse confirmBooking(UUID bookingId, UUID userId, ConfirmBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.HELD) {
            throw new BookingNotHeldException(bookingId, booking.getStatus());
        }

        booking.setStatus(BookingStatus.PAYMENT_PENDING);
        booking = bookingRepository.save(booking);

        eventPublisher.publishPaymentInitiated(booking, request.getStripePaymentMethodId());
        log.info("Payment initiated: booking={}", bookingId);

        return BookingResponse.from(booking);
    }

    public BookingResponse getBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
            .map(BookingResponse::from)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    @Transactional
    public void handlePaymentCompleted(UUID bookingId, String stripePaymentIntentId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Booking {} already confirmed — skipping duplicate event", bookingId);
            return;
        }
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            log.warn("Booking {} in unexpected status {} for payment-completed", bookingId, booking.getStatus());
            return;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(Instant.now());
        booking.setTicketReference(generateTicketReference());
        bookingRepository.save(booking);

        seatHoldService.releaseHold(booking.getSeatId());
        eventPublisher.publishTicketIssued(booking);

        log.info("Booking confirmed: booking={} ticket={}", bookingId, booking.getTicketReference());
    }

    @Transactional
    public void handlePaymentFailed(UUID bookingId, String failureReason) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            return;
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        bookingRepository.save(booking);

        seatHoldService.releaseHold(booking.getSeatId());
        log.info("Booking cancelled after payment failure: booking={} reason={}", bookingId, failureReason);
    }

    @Transactional
    public void expireHold(UUID seatId) {
        bookingRepository.findBySeatIdAndStatus(seatId, BookingStatus.HELD).ifPresentOrElse(booking -> {
            booking.setStatus(BookingStatus.EXPIRED);
            booking.setExpiredAt(Instant.now());
            bookingRepository.save(booking);

            eventPublisher.publishSeatHoldExpired(booking);
            log.info("Seat hold expired: booking={} seat={}", booking.getId(), seatId);
        }, () -> log.debug("No HELD booking found for expired seat hold: seat={}", seatId));
    }

    private String generateTicketReference() {
        String year = String.valueOf(LocalDate.now().getYear());
        String suffix = UUID.randomUUID().toString().toUpperCase().replace("-", "").substring(0, 4);
        return "TKT-" + year + "-" + suffix;
    }
}
