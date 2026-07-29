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

    @Transactional
    public void recordSeatHoldExpired(UUID bookingId, UUID userId, String userEmail, UUID seatId, UUID eventId) {
        if (notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.SEAT_HOLD_EXPIRED)) {
            log.info("Seat-hold-expired notification already exists for booking {} — skipping duplicate", bookingId);
            return;
        }

        String subject = "Your seat hold has expired";
        String body = "Hi,\n\nYour hold on seat " + seatId + " for event " + eventId + " has expired "
            + "because it wasn't confirmed in time. The seat has been released.\n\n"
            + "Feel free to try again, or join the waitlist if it's sold out.";

        SendResult result = notificationSender.send(userEmail, subject, body);

        Notification notification = new Notification();
        notification.setBookingId(bookingId);
        notification.setUserId(userId);
        notification.setUserEmail(userEmail);
        notification.setType(NotificationType.SEAT_HOLD_EXPIRED);
        notification.setStatus(result.success() ? NotificationStatus.SENT : NotificationStatus.FAILED);
        notification.setSubject(subject);
        notification.setBody(body);
        notificationRepository.save(notification);

        log.info("Notification {} for booking {}: {}", notification.getStatus(), bookingId, subject);
    }

    @Transactional
    public void recordBookingCancelled(UUID bookingId, UUID userId, String userEmail, UUID seatId, UUID eventId,
                                        Double amountGbp) {
        if (notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.BOOKING_CANCELLED)) {
            log.info("Booking-cancelled notification already exists for booking {} — skipping duplicate", bookingId);
            return;
        }

        String subject = "Your booking has been cancelled";
        String body = "Hi,\n\nYour booking " + bookingId + " (seat " + seatId + ", event " + eventId + ") "
            + "has been cancelled.\n\n"
            + (amountGbp != null ? "A refund of £" + amountGbp + " will be processed.\n\n" : "")
            + "We're sorry to see you go!";

        SendResult result = notificationSender.send(userEmail, subject, body);

        Notification notification = new Notification();
        notification.setBookingId(bookingId);
        notification.setUserId(userId);
        notification.setUserEmail(userEmail);
        notification.setType(NotificationType.BOOKING_CANCELLED);
        notification.setStatus(result.success() ? NotificationStatus.SENT : NotificationStatus.FAILED);
        notification.setSubject(subject);
        notification.setBody(body);
        notificationRepository.save(notification);

        log.info("Notification {} for booking {}: {}", notification.getStatus(), bookingId, subject);
    }

    @Transactional
    public void recordWaitlistPromoted(UUID waitlistEntryId, UUID userId, String userEmail, UUID eventId,
                                        String eventTitle, Instant offerExpiresAt) {
        if (notificationRepository.existsByWaitlistEntryIdAndType(waitlistEntryId, NotificationType.WAITLIST_PROMOTED)) {
            log.info("Waitlist-promoted notification already exists for entry {} — skipping duplicate", waitlistEntryId);
            return;
        }

        String subject = "A seat is available for " + eventTitle;
        String body = "Hi,\n\nGood news — a seat for " + eventTitle + " has opened up and it's your turn!\n\n"
            + "You have until " + offerExpiresAt + " to book before it's offered to the next person.";

        SendResult result = notificationSender.send(userEmail, subject, body);

        Notification notification = new Notification();
        notification.setWaitlistEntryId(waitlistEntryId);
        notification.setUserId(userId);
        notification.setUserEmail(userEmail);
        notification.setType(NotificationType.WAITLIST_PROMOTED);
        notification.setStatus(result.success() ? NotificationStatus.SENT : NotificationStatus.FAILED);
        notification.setSubject(subject);
        notification.setBody(body);
        notificationRepository.save(notification);

        log.info("Notification {} for waitlist entry {}: {}", notification.getStatus(), waitlistEntryId, subject);
    }

    public NotificationResponse getNotification(UUID notificationId) {
        return notificationRepository.findById(notificationId)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    public NotificationResponse getNotificationByBooking(UUID bookingId) {
        return notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundForBookingException(bookingId));
    }

    public NotificationResponse getNotificationByWaitlistEntry(UUID waitlistEntryId) {
        return notificationRepository.findByWaitlistEntryId(waitlistEntryId)
            .map(NotificationResponse::from)
            .orElseThrow(() -> new NotificationNotFoundForWaitlistEntryException(waitlistEntryId));
    }
}
