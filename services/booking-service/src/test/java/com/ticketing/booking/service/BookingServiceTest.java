package com.ticketing.booking.service;

import com.ticketing.booking.client.EventInfo;
import com.ticketing.booking.client.EventServiceClient;
import com.ticketing.booking.client.SeatInfo;
import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.ConfirmBookingRequest;
import com.ticketing.booking.dto.HoldSeatRequest;
import com.ticketing.booking.exception.BookingNotCancellableException;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.exception.BookingNotHeldException;
import com.ticketing.booking.exception.EventNotFoundException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import com.ticketing.booking.exception.SeatNotFoundException;
import com.ticketing.booking.kafka.producer.BookingEventPublisher;
import com.ticketing.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SeatHoldService seatHoldService;
    @Mock BookingEventPublisher eventPublisher;
    @Mock EventServiceClient eventServiceClient;
    @Mock com.ticketing.booking.websocket.SeatStatusWebSocketHandler seatStatusWebSocketHandler;

    @InjectMocks BookingService bookingService;

    private UUID userId;
    private UUID eventId;
    private UUID seatId;

    @BeforeEach
    void setUp() {
        userId  = UUID.randomUUID();
        eventId = UUID.randomUUID();
        seatId  = UUID.randomUUID();
    }

    // ── holdSeat ──────────────────────────────────────────────────────────────

    @Test
    void holdSeat_whenSeatFreeAndRedisAcquired_returnsHeldBooking() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willReturn(new EventInfo(eventId, "Test Event"));
        given(eventServiceClient.getSeat(eventId, seatId)).willReturn(new SeatInfo(seatId, "B7", 89.5));
        given(seatHoldService.acquireHold(seatId)).willReturn(true);
        given(bookingRepository.save(any())).willAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(UUID.randomUUID()); // simulate JPA-generated ID
            return b;
        });

        BookingResponse response = bookingService.holdSeat(userId, holdRequest());

        assertThat(response.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(response.getEventTitle()).isEqualTo("Test Event");
        assertThat(response.getSeatLabel()).isEqualTo("B7");
        assertThat(response.getTotalAmountGbp()).isEqualTo(89.5);
        assertThat(response.getHoldExpiresAt()).isAfter(Instant.now());
        assertThat(response.getUserId()).isEqualTo(userId);
    }

    @Test
    void holdSeat_whenDbAlreadyHasActiveBookingForSeat_throwsSeatAlreadyHeld() {
        // Redis is not even reached — DB check rejects first
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(true);

        assertThatThrownBy(() -> bookingService.holdSeat(userId, holdRequest()))
            .isInstanceOf(SeatAlreadyHeldException.class)
            .hasMessageContaining(seatId.toString());

        then(seatHoldService).shouldHaveNoInteractions();
        then(bookingRepository).should(never()).save(any());
    }

    @Test
    void holdSeat_whenRedisLockAlreadyHeld_throwsSeatAlreadyHeld() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(seatHoldService.acquireHold(seatId)).willReturn(false);

        assertThatThrownBy(() -> bookingService.holdSeat(userId, holdRequest()))
            .isInstanceOf(SeatAlreadyHeldException.class);

        then(bookingRepository).should(never()).save(any());
    }

    @Test
    void holdSeat_whenDbSaveFails_releasesRedisLockToPreventLeak() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willReturn(new EventInfo(eventId, "Test Event"));
        given(eventServiceClient.getSeat(eventId, seatId)).willReturn(new SeatInfo(seatId, "B7", 89.5));
        given(seatHoldService.acquireHold(seatId)).willReturn(true);
        given(bookingRepository.save(any())).willThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() -> bookingService.holdSeat(userId, holdRequest()))
            .isInstanceOf(RuntimeException.class);

        // Redis lock must be released so the seat isn't orphaned for 10 minutes
        then(seatHoldService).should().releaseHold(seatId);
    }

    @Test
    void holdSeat_usesEventTitleAndSeatLabelAndPriceFromEventService() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willReturn(new EventInfo(eventId, "Real Event Title"));
        given(eventServiceClient.getSeat(eventId, seatId)).willReturn(new SeatInfo(seatId, "Floor-R3-S12", 120.0));
        given(seatHoldService.acquireHold(seatId)).willReturn(true);
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(eventId);
        req.setSeatId(seatId);

        BookingResponse response = bookingService.holdSeat(userId, req);

        assertThat(response.getEventTitle()).isEqualTo("Real Event Title");
        assertThat(response.getSeatLabel()).isEqualTo("Floor-R3-S12");
        assertThat(response.getTotalAmountGbp()).isEqualTo(120.0);
    }

    @Test
    void holdSeat_whenEventNotFound_throwsEventNotFoundException() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willThrow(new EventNotFoundException(eventId));

        assertThatThrownBy(() -> bookingService.holdSeat(userId, holdRequest()))
            .isInstanceOf(EventNotFoundException.class)
            .hasMessageContaining(eventId.toString());

        then(seatHoldService).shouldHaveNoInteractions();
        then(bookingRepository).should(never()).save(any());
    }

    @Test
    void holdSeat_whenSeatNotFound_throwsSeatNotFoundException() {
        given(bookingRepository.existsBySeatIdAndStatusIn(eq(seatId), anyList())).willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willReturn(new EventInfo(eventId, "Test Event"));
        given(eventServiceClient.getSeat(eventId, seatId)).willThrow(new SeatNotFoundException(eventId, seatId));

        assertThatThrownBy(() -> bookingService.holdSeat(userId, holdRequest()))
            .isInstanceOf(SeatNotFoundException.class);

        then(seatHoldService).shouldHaveNoInteractions();
        then(bookingRepository).should(never()).save(any());
    }

    // ── confirmBooking ────────────────────────────────────────────────────────

    @Test
    void confirmBooking_whenHeld_transitionsToPaymentPendingAndPublishesEvent() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.HELD);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.confirmBooking(bookingId, userId, confirmRequest("pm_test_4242"));

        assertThat(response.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);

        ArgumentCaptor<Booking> publishedBooking = ArgumentCaptor.forClass(Booking.class);
        then(eventPublisher).should().publishPaymentInitiated(publishedBooking.capture(), eq("pm_test_4242"));
        assertThat(publishedBooking.getValue().getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
    }

    @Test
    void confirmBooking_whenAlreadyConfirmed_throwsBookingNotHeld() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId))
            .willReturn(Optional.of(aBooking(bookingId, seatId, BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> bookingService.confirmBooking(bookingId, userId, confirmRequest("pm_x")))
            .isInstanceOf(BookingNotHeldException.class)
            .hasMessageContaining("CONFIRMED");

        then(bookingRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void confirmBooking_whenExpired_throwsBookingNotHeld() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId))
            .willReturn(Optional.of(aBooking(bookingId, seatId, BookingStatus.EXPIRED)));

        assertThatThrownBy(() -> bookingService.confirmBooking(bookingId, userId, confirmRequest("pm_x")))
            .isInstanceOf(BookingNotHeldException.class)
            .hasMessageContaining("EXPIRED");
    }

    @Test
    void confirmBooking_whenBookingNotFound_throwsBookingNotFound() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.confirmBooking(bookingId, userId, confirmRequest("pm_x")))
            .isInstanceOf(BookingNotFoundException.class)
            .hasMessageContaining(bookingId.toString());
    }

    // ── handlePaymentCompleted ────────────────────────────────────────────────

    @Test
    void handlePaymentCompleted_whenPaymentPending_confirmsAndGeneratesTicketAndPublishes() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.PAYMENT_PENDING);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        bookingService.handlePaymentCompleted(bookingId, "pi_demo_abc123");

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        then(bookingRepository).should().save(saved.capture());

        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getValue().getConfirmedAt()).isNotNull();
        assertThat(saved.getValue().getTicketReference()).matches("TKT-\\d{4}-[A-F0-9]{4}");

        then(seatHoldService).should().releaseHold(seatId);
        then(eventPublisher).should().publishTicketIssued(any());
    }

    @Test
    void handlePaymentCompleted_whenAlreadyConfirmed_skipsIdempotently() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId))
            .willReturn(Optional.of(aBooking(bookingId, seatId, BookingStatus.CONFIRMED)));

        // Duplicate payment-completed event (e.g., Kafka redelivery) — must be a no-op
        bookingService.handlePaymentCompleted(bookingId, "pi_demo_abc123");

        then(bookingRepository).should(never()).save(any());
        then(seatHoldService).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void handlePaymentCompleted_eachCallGeneratesUniqueTicketReference() {
        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        UUID seatId2 = UUID.randomUUID();

        Booking b1 = aBooking(bookingId1, seatId, BookingStatus.PAYMENT_PENDING);
        Booking b2 = aBooking(bookingId2, seatId2, BookingStatus.PAYMENT_PENDING);

        given(bookingRepository.findById(bookingId1)).willReturn(Optional.of(b1));
        given(bookingRepository.findById(bookingId2)).willReturn(Optional.of(b2));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        bookingService.handlePaymentCompleted(bookingId1, "pi_1");
        bookingService.handlePaymentCompleted(bookingId2, "pi_2");

        assertThat(b1.getTicketReference()).isNotEqualTo(b2.getTicketReference());
    }

    // ── handlePaymentFailed ───────────────────────────────────────────────────

    @Test
    void handlePaymentFailed_whenPaymentPending_cancelledAndReleasesLock() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.PAYMENT_PENDING);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        bookingService.handlePaymentFailed(bookingId, "card_declined");

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        then(bookingRepository).should().save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(saved.getValue().getCancelledAt()).isNotNull();

        then(seatHoldService).should().releaseHold(seatId);
        then(eventPublisher).should().publishSeatReleased(booking);
    }

    // ── cancelBooking ─────────────────────────────────────────────────────────

    @Test
    void cancelBooking_whenHeld_releasesRedisAndCancelsWithoutPublishing() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.HELD);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.cancelBooking(bookingId, userId);

        assertThat(response.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        then(seatHoldService).should().releaseHold(seatId);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void cancelBooking_whenConfirmed_cancelsAndPublishesBookingCancelled() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.CONFIRMED);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.cancelBooking(bookingId, userId);

        assertThat(response.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        then(seatHoldService).shouldHaveNoInteractions();
        then(eventPublisher).should().publishBookingCancelled(booking);
    }

    @Test
    void cancelBooking_whenPaymentPending_throwsBookingNotCancellable() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId))
            .willReturn(Optional.of(aBooking(bookingId, seatId, BookingStatus.PAYMENT_PENDING)));

        assertThatThrownBy(() -> bookingService.cancelBooking(bookingId, userId))
            .isInstanceOf(BookingNotCancellableException.class)
            .hasMessageContaining("PAYMENT_PENDING");

        then(bookingRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void cancelBooking_whenAlreadyCancelled_throwsBookingNotCancellable() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId))
            .willReturn(Optional.of(aBooking(bookingId, seatId, BookingStatus.CANCELLED)));

        assertThatThrownBy(() -> bookingService.cancelBooking(bookingId, userId))
            .isInstanceOf(BookingNotCancellableException.class);
    }

    @Test
    void cancelBooking_whenNotFound_throwsBookingNotFound() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.cancelBooking(bookingId, userId))
            .isInstanceOf(BookingNotFoundException.class);
    }

    // ── expireHold ────────────────────────────────────────────────────────────

    @Test
    void expireHold_whenHeldBookingExistsForSeat_transitionsToExpiredAndPublishes() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.HELD);
        given(bookingRepository.findBySeatIdAndStatus(seatId, BookingStatus.HELD)).willReturn(Optional.of(booking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        bookingService.expireHold(seatId);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        then(bookingRepository).should().save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(saved.getValue().getExpiredAt()).isNotNull();

        then(eventPublisher).should().publishSeatHoldExpired(booking);
    }

    @Test
    void expireHold_whenNoHeldBookingForSeat_isNoOp() {
        given(bookingRepository.findBySeatIdAndStatus(seatId, BookingStatus.HELD)).willReturn(Optional.empty());

        bookingService.expireHold(seatId);

        then(bookingRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    // ── getActiveSeatsForEvent ────────────────────────────────────────────────

    @Test
    void getActiveSeatsForEvent_returnsHeldPaymentPendingAndConfirmedBookings() {
        Booking held = aBooking(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.HELD);
        Booking confirmed = aBooking(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);
        given(bookingRepository.findByEventIdAndStatusIn(eventId,
            List.of(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED)))
            .willReturn(List.of(held, confirmed));

        var result = bookingService.getActiveSeatsForEvent(eventId);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(r -> {
            assertThat(r.getSeatId()).isEqualTo(held.getSeatId());
            assertThat(r.getStatus()).isEqualTo(BookingStatus.HELD);
        });
        assertThat(result).anySatisfy(r -> {
            assertThat(r.getSeatId()).isEqualTo(confirmed.getSeatId());
            assertThat(r.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        });
    }

    @Test
    void getActiveSeatsForEvent_whenNoneActive_returnsEmptyList() {
        given(bookingRepository.findByEventIdAndStatusIn(eq(eventId), anyList())).willReturn(List.of());

        assertThat(bookingService.getActiveSeatsForEvent(eventId)).isEmpty();
    }

    // ── getBooking ────────────────────────────────────────────────────────────

    @Test
    void getBooking_whenExists_returnsCorrectResponse() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = aBooking(bookingId, seatId, BookingStatus.CONFIRMED);
        given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

        BookingResponse response = bookingService.getBooking(bookingId);

        assertThat(response.getId()).isEqualTo(bookingId);
        assertThat(response.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.getEventTitle()).isEqualTo("Test Event");
    }

    @Test
    void getBooking_whenNotFound_throwsBookingNotFoundException() {
        UUID bookingId = UUID.randomUUID();
        given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getBooking(bookingId))
            .isInstanceOf(BookingNotFoundException.class)
            .hasMessageContaining(bookingId.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HoldSeatRequest holdRequest() {
        HoldSeatRequest req = new HoldSeatRequest();
        req.setEventId(eventId);
        req.setSeatId(seatId);
        return req;
    }

    private ConfirmBookingRequest confirmRequest(String paymentMethodId) {
        ConfirmBookingRequest req = new ConfirmBookingRequest();
        req.setStripePaymentMethodId(paymentMethodId);
        return req;
    }

    private Booking aBooking(UUID id, UUID seatId, BookingStatus status) {
        Booking b = new Booking();
        b.setId(id);
        b.setEventId(eventId);
        b.setEventTitle("Test Event");
        b.setSeatId(seatId);
        b.setSeatLabel("B7");
        b.setUserId(userId);
        b.setStatus(status);
        b.setTotalAmountGbp(89.5);
        b.setHoldExpiresAt(Instant.now().plusSeconds(600));
        return b;
    }
}
