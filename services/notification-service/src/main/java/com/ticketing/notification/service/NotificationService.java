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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    public NotificationService(NotificationRepository notificationRepository,
                                NotificationSender notificationSender) {
        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
    }

    @Transactional
    public void recordTicketIssued(UUID bookingId, UUID userId, String userEmail, String ticketReference,
                                    String eventTitle, String venueName, String seatLabel, Instant startsAt) {
        if (notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.TICKET_ISSUED)) {
            log.info("Ticket-issued notification already exists for booking {} — skipping duplicate", bookingId);
            return;
        }

        String subject = "Your ticket for " + eventTitle;
        String body = "Hi,\n\nYour booking is confirmed!\n\n"
            + "Ticket reference: " + ticketReference + "\n"
            + "Event: " + eventTitle + "\n"
            + (venueName != null ? "Venue: " + venueName + "\n" : "")
            + "Seat: " + seatLabel + "\n"
            + "Starts at: " + startsAt + "\n\n"
            + "See you there!";

        SendResult result = notificationSender.send(userEmail, subject, body);

        Notification notification = new Notification();
        notification.setBookingId(bookingId);
        notification.setUserId(userId);
        notification.setUserEmail(userEmail);
        notification.setType(NotificationType.TICKET_ISSUED);
        notification.setStatus(result.success() ? NotificationStatus.SENT : NotificationStatus.FAILED);
        notification.setSubject(subject);
        notification.setBody(body);
        notificationRepository.save(notification);

        log.info("Notification {} for booking {}: {}", notification.getStatus(), bookingId, subject);
    }

    public NotificationResponse getNotification(UUID notificationId) {
        return notificationRepository.findById(notificationId)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    public NotificationResponse getNotificationByBooking(UUID bookingId) {
        return notificationRepository.findByBookingId(bookingId)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundForBookingException(bookingId));
    }
}
