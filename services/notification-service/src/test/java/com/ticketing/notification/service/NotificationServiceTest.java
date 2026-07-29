package com.ticketing.notification.service;

import com.ticketing.notification.client.NotificationSender;
import com.ticketing.notification.client.SendResult;
import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.domain.NotificationType;
import com.ticketing.notification.dto.NotificationResponse;
import com.ticketing.notification.exception.NotificationNotFoundException;
import com.ticketing.notification.exception.NotificationNotFoundForBookingException;
import com.ticketing.notification.exception.NotificationNotFoundForWaitlistEntryException;
import com.ticketing.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationSender notificationSender;

    @InjectMocks NotificationService notificationService;

    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    // ── recordTicketIssued ───────────────────────────────────────────────────

    @Test
    void recordTicketIssued_whenSendSucceeds_savesSentNotification() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.TICKET_ISSUED)).willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.succeeded());

        notificationService.recordTicketIssued(bookingId, userId, "user@example.com", "TKT-1", "Test Event",
            "Test Venue", "A1", Instant.parse("2026-08-01T19:00:00Z"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(bookingId);
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getUserEmail()).isEqualTo("user@example.com");
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.TICKET_ISSUED);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getValue().getSubject()).contains("Test Event");
        assertThat(saved.getValue().getBody()).contains("TKT-1", "Test Venue", "A1");
    }

    @Test
    void recordTicketIssued_whenSendFails_savesFailedNotification() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.TICKET_ISSUED)).willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.failed("smtp down"));

        notificationService.recordTicketIssued(bookingId, userId, "user@example.com", "TKT-1", "Test Event",
            null, "A1", Instant.parse("2026-08-01T19:00:00Z"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void recordTicketIssued_whenAlreadyExistsForBooking_isNoOp() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.TICKET_ISSUED)).willReturn(true);

        notificationService.recordTicketIssued(bookingId, userId, "user@example.com", "TKT-1", "Test Event",
            "Test Venue", "A1", Instant.now());

        then(notificationSender).shouldHaveNoInteractions();
        then(notificationRepository).should(never()).save(any());
    }

    // ── recordSeatHoldExpired ─────────────────────────────────────────────────

    @Test
    void recordSeatHoldExpired_whenSendSucceeds_savesSentNotification() {
        UUID seatId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.SEAT_HOLD_EXPIRED)).willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.succeeded());

        notificationService.recordSeatHoldExpired(bookingId, userId, "user@example.com", seatId, eventId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(bookingId);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.SEAT_HOLD_EXPIRED);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getValue().getBody()).contains(seatId.toString(), eventId.toString());
    }

    @Test
    void recordSeatHoldExpired_whenAlreadyExistsForBooking_isNoOp() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.SEAT_HOLD_EXPIRED)).willReturn(true);

        notificationService.recordSeatHoldExpired(bookingId, userId, "user@example.com", UUID.randomUUID(), UUID.randomUUID());

        then(notificationSender).shouldHaveNoInteractions();
        then(notificationRepository).should(never()).save(any());
    }

    // ── recordBookingCancelled ────────────────────────────────────────────────

    @Test
    void recordBookingCancelled_whenSendSucceeds_savesSentNotification() {
        UUID seatId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.BOOKING_CANCELLED)).willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.succeeded());

        notificationService.recordBookingCancelled(bookingId, userId, "user@example.com", seatId, eventId, 89.5);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.BOOKING_CANCELLED);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getValue().getBody()).contains("89.5");
    }

    @Test
    void recordBookingCancelled_whenAmountGbpNull_omitsRefundLine() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.BOOKING_CANCELLED)).willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.succeeded());

        notificationService.recordBookingCancelled(bookingId, userId, "user@example.com",
            UUID.randomUUID(), UUID.randomUUID(), null);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getBody()).doesNotContain("refund");
    }

    @Test
    void recordBookingCancelled_whenAlreadyExistsForBooking_isNoOp() {
        given(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.BOOKING_CANCELLED)).willReturn(true);

        notificationService.recordBookingCancelled(bookingId, userId, "user@example.com",
            UUID.randomUUID(), UUID.randomUUID(), 50.0);

        then(notificationSender).shouldHaveNoInteractions();
        then(notificationRepository).should(never()).save(any());
    }

    // ── recordWaitlistPromoted ────────────────────────────────────────────────

    @Test
    void recordWaitlistPromoted_whenSendSucceeds_savesSentNotificationWithWaitlistEntryId() {
        UUID waitlistEntryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant offerExpiresAt = Instant.now().plusSeconds(900);
        given(notificationRepository.existsByWaitlistEntryIdAndType(waitlistEntryId, NotificationType.WAITLIST_PROMOTED))
            .willReturn(false);
        given(notificationSender.send(anyString(), anyString(), anyString())).willReturn(SendResult.succeeded());

        notificationService.recordWaitlistPromoted(waitlistEntryId, userId, "user@example.com", eventId,
            "Test Event", offerExpiresAt);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(saved.capture());
        assertThat(saved.getValue().getWaitlistEntryId()).isEqualTo(waitlistEntryId);
        assertThat(saved.getValue().getBookingId()).isNull();
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.WAITLIST_PROMOTED);
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getValue().getSubject()).contains("Test Event");
    }

    @Test
    void recordWaitlistPromoted_whenAlreadyExistsForEntry_isNoOp() {
        UUID waitlistEntryId = UUID.randomUUID();
        given(notificationRepository.existsByWaitlistEntryIdAndType(waitlistEntryId, NotificationType.WAITLIST_PROMOTED))
            .willReturn(true);

        notificationService.recordWaitlistPromoted(waitlistEntryId, userId, "user@example.com",
            UUID.randomUUID(), "Test Event", Instant.now());

        then(notificationSender).shouldHaveNoInteractions();
        then(notificationRepository).should(never()).save(any());
    }

    // ── getNotification / getNotificationByBooking ──────────────────────────

    @Test
    void getNotification_whenExists_returnsResponse() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = aNotification(bookingId);
        given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

        NotificationResponse response = notificationService.getNotification(notificationId);

        assertThat(response.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void getNotification_whenNotFound_throwsNotificationNotFoundException() {
        UUID notificationId = UUID.randomUUID();
        given(notificationRepository.findById(notificationId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotification(notificationId))
            .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void getNotificationByBooking_whenExists_returnsResponse() {
        Notification notification = aNotification(bookingId);
        given(notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)).willReturn(Optional.of(notification));

        NotificationResponse response = notificationService.getNotificationByBooking(bookingId);

        assertThat(response.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void getNotificationByBooking_whenNotFound_throwsNotificationNotFoundForBookingException() {
        given(notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationByBooking(bookingId))
            .isInstanceOf(NotificationNotFoundForBookingException.class);
    }

    @Test
    void getNotificationByWaitlistEntry_whenExists_returnsResponse() {
        UUID waitlistEntryId = UUID.randomUUID();
        Notification notification = aNotification(bookingId);
        notification.setWaitlistEntryId(waitlistEntryId);
        given(notificationRepository.findByWaitlistEntryId(waitlistEntryId)).willReturn(Optional.of(notification));

        NotificationResponse response = notificationService.getNotificationByWaitlistEntry(waitlistEntryId);

        assertThat(response.getWaitlistEntryId()).isEqualTo(waitlistEntryId);
    }

    @Test
    void getNotificationByWaitlistEntry_whenNotFound_throwsNotificationNotFoundForWaitlistEntryException() {
        UUID waitlistEntryId = UUID.randomUUID();
        given(notificationRepository.findByWaitlistEntryId(waitlistEntryId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationByWaitlistEntry(waitlistEntryId))
            .isInstanceOf(NotificationNotFoundForWaitlistEntryException.class);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Notification aNotification(UUID bookingId) {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setBookingId(bookingId);
        n.setUserId(UUID.randomUUID());
        n.setUserEmail("user@example.com");
        n.setType(NotificationType.TICKET_ISSUED);
        n.setStatus(NotificationStatus.SENT);
        n.setSubject("Your ticket for Test Event");
        n.setBody("body text");
        return n;
    }
}
