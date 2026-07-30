package com.ticketing.booking.service;

import com.ticketing.booking.client.EventInfo;
import com.ticketing.booking.client.EventServiceClient;
import com.ticketing.booking.client.SeatInfo;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.ActiveSeatResponse;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.EventSeatCountResponse;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.exception.BookingNotCancellableException;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.exception.BookingNotHeldException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import com.ticketing.booking.kafka.producer.BookingEventPublisher;
import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.websocket.SeatStatusWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final SeatHoldService seatHoldService;
    private final BookingEventPublisher eventPublisher;
    private final EventServiceClient eventServiceClient;
    private final SeatStatusWebSocketHandler seatStatusWebSocketHandler;

    public BookingService(BookingRepository bookingRepository,
                          SeatHoldService seatHoldService,
                          BookingEventPublisher eventPublisher,
                          EventServiceClient eventServiceClient,
                          SeatStatusWebSocketHandler seatStatusWebSocketHandler) {
        this.bookingRepository = bookingRepository;
        this.seatHoldService = seatHoldService;
        this.eventPublisher = eventPublisher;
        this.eventServiceClient = eventServiceClient;
        this.seatStatusWebSocketHandler = seatStatusWebSocketHandler;
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

        // Validates the seat is real and fetches its real label/price from event-service
        SeatInfo seat = eventServiceClient.getSeat(request.getEventId(), request.getSeatId());

        boolean acquired = seatHoldService.acquireHold(request.getSeatId());
        if (!acquired) {
            throw new SeatAlreadyHeldException(request.getSeatId());
        }

        Booking booking = new Booking();
        booking.setEventId(event.id());
        booking.setEventTitle(event.title());
        booking.setSeatId(request.getSeatId());
        booking.setSeatLabel(seat.label());
        booking.setUserId(userId);
        booking.setStatus(BookingStatus.HELD);
        booking.setTotalAmountGbp(seat.priceGbp());
        booking.setHoldExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));

        try {
            booking = bookingRepository.save(booking);
            log.info("Seat hold created: booking={} seat={}", booking.getId(), request.getSeatId());
            seatStatusWebSocketHandler.broadcast(booking.getEventId(), booking.getSeatId(), "HELD");
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

    public List<ActiveSeatResponse> getActiveSeatsForEvent(UUID eventId) {
        return bookingRepository.findByEventIdAndStatusIn(eventId,
                List.of(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED))
            .stream()
            .map(ActiveSeatResponse::from)
            .toList();
    }

    public List<EventSeatCountResponse> getActiveSeatCounts(List<UUID> eventIds) {
        Map<UUID, Long> countsByEvent = bookingRepository.findByEventIdInAndStatusIn(eventIds,
                List.of(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED))
            .stream()
            .collect(Collectors.groupingBy(Booking::getEventId, Collectors.counting()));

        return eventIds.stream()
            .map(eventId -> new EventSeatCountResponse(eventId, countsByEvent.getOrDefault(eventId, 0L).intValue()))
            .toList();
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
        seatStatusWebSocketHandler.broadcast(booking.getEventId(), booking.getSeatId(), "BOOKED");

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
        eventPublisher.publishSeatReleased(booking);
        seatStatusWebSocketHandler.broadcast(booking.getEventId(), booking.getSeatId(), "AVAILABLE");
        log.info("Booking cancelled after payment failure: booking={} reason={}", bookingId, failureReason);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        switch (booking.getStatus()) {
            case HELD -> {
                seatHoldService.releaseHold(booking.getSeatId());
                booking.setStatus(BookingStatus.CANCELLED);
                booking.setCancelledAt(Instant.now());
                booking = bookingRepository.save(booking);
                log.info("HELD booking cancelled: booking={}", bookingId);
            }
            case CONFIRMED -> {
                booking.setStatus(BookingStatus.CANCELLED);
                booking.setCancelledAt(Instant.now());
                booking = bookingRepository.save(booking);
                eventPublisher.publishBookingCancelled(booking);
                log.info("CONFIRMED booking cancelled: booking={}", bookingId);
            }
            default -> throw new BookingNotCancellableException(bookingId, booking.getStatus());
        }

        seatStatusWebSocketHandler.broadcast(booking.getEventId(), booking.getSeatId(), "AVAILABLE");
        return BookingResponse.from(booking);
    }

    @Transactional
    public void expireHold(UUID seatId) {
        bookingRepository.findBySeatIdAndStatus(seatId, BookingStatus.HELD).ifPresentOrElse(booking -> {
            booking.setStatus(BookingStatus.EXPIRED);
            booking.setExpiredAt(Instant.now());
            bookingRepository.save(booking);

            eventPublisher.publishSeatHoldExpired(booking);
            seatStatusWebSocketHandler.broadcast(booking.getEventId(), booking.getSeatId(), "AVAILABLE");
            log.info("Seat hold expired: booking={} seat={}", booking.getId(), seatId);
        }, () -> log.debug("No HELD booking found for expired seat hold: seat={}", seatId));
    }

    private String generateTicketReference() {
        String year = String.valueOf(LocalDate.now().getYear());
        String suffix = UUID.randomUUID().toString().toUpperCase().replace("-", "").substring(0, 4);
        return "TKT-" + year + "-" + suffix;
    }
}
