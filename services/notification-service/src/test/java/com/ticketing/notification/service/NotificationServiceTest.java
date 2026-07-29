package com.ticketing.notification.service;

import com.ticketing.notification.client.NotificationSender;
import com.ticketing.notification.client.SendResult;
import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.domain.NotificationType;
import com.ticketing.notification.dto.NotificationResponse;
import com.ticketing.notification.exception.NotificationNotFoundException;
import com.ticketing.notification.exception.NotificationNotFoundForBookingException;
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
        given(notificationRepository.findByBookingId(bookingId)).willReturn(Optional.of(notification));

        NotificationResponse response = notificationService.getNotificationByBooking(bookingId);

        assertThat(response.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void getNotificationByBooking_whenNotFound_throwsNotificationNotFoundForBookingException() {
        given(notificationRepository.findByBookingId(bookingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationByBooking(bookingId))
            .isInstanceOf(NotificationNotFoundForBookingException.class);
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
