package com.ticketing.notification.repository;

import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class NotificationRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    NotificationRepository notificationRepository;

    @Test
    void findFirstByBookingIdOrderByCreatedAtDesc_whenExists_returnsNotification() {
        UUID bookingId = UUID.randomUUID();
        notificationRepository.save(aNotification(bookingId));

        Optional<Notification> result = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId);

        assertThat(result).isPresent();
        assertThat(result.get().getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void findFirstByBookingIdOrderByCreatedAtDesc_whenMultipleExist_returnsMostRecent() {
        UUID bookingId = UUID.randomUUID();
        Notification older = aNotification(bookingId);
        older.setType(NotificationType.TICKET_ISSUED);
        older.setCreatedAt(java.time.Instant.now().minusSeconds(60));
        notificationRepository.save(older);
        Notification newer = aNotification(bookingId);
        newer.setType(NotificationType.BOOKING_CANCELLED);
        newer.setCreatedAt(java.time.Instant.now());
        notificationRepository.save(newer);

        Optional<Notification> result = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId);

        assertThat(result).isPresent();
        assertThat(result.get().getType()).isEqualTo(NotificationType.BOOKING_CANCELLED);
    }

    @Test
    void findFirstByBookingIdOrderByCreatedAtDesc_whenAbsent_returnsEmpty() {
        Optional<Notification> result = notificationRepository.findFirstByBookingIdOrderByCreatedAtDesc(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void existsByBookingIdAndType_whenExists_returnsTrue() {
        UUID bookingId = UUID.randomUUID();
        notificationRepository.save(aNotification(bookingId));

        assertThat(notificationRepository.existsByBookingIdAndType(bookingId, NotificationType.TICKET_ISSUED)).isTrue();
    }

    @Test
    void existsByBookingIdAndType_whenAbsent_returnsFalse() {
        assertThat(notificationRepository.existsByBookingIdAndType(UUID.randomUUID(), NotificationType.TICKET_ISSUED)).isFalse();
    }

    @Test
    void findByWaitlistEntryId_whenExists_returnsNotification() {
        UUID waitlistEntryId = UUID.randomUUID();
        notificationRepository.save(aWaitlistPromotedNotification(waitlistEntryId));

        Optional<Notification> result = notificationRepository.findByWaitlistEntryId(waitlistEntryId);

        assertThat(result).isPresent();
        assertThat(result.get().getWaitlistEntryId()).isEqualTo(waitlistEntryId);
        assertThat(result.get().getBookingId()).isNull();
    }

    @Test
    void findByWaitlistEntryId_whenAbsent_returnsEmpty() {
        Optional<Notification> result = notificationRepository.findByWaitlistEntryId(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void existsByWaitlistEntryIdAndType_whenExists_returnsTrue() {
        UUID waitlistEntryId = UUID.randomUUID();
        notificationRepository.save(aWaitlistPromotedNotification(waitlistEntryId));

        assertThat(notificationRepository.existsByWaitlistEntryIdAndType(waitlistEntryId, NotificationType.WAITLIST_PROMOTED))
            .isTrue();
    }

    @Test
    void existsByWaitlistEntryIdAndType_whenAbsent_returnsFalse() {
        assertThat(notificationRepository.existsByWaitlistEntryIdAndType(UUID.randomUUID(), NotificationType.WAITLIST_PROMOTED))
            .isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Notification aNotification(UUID bookingId) {
        Notification n = new Notification();
        n.setBookingId(bookingId);
        n.setUserId(UUID.randomUUID());
        n.setUserEmail("demo@ticketing.com");
        n.setType(NotificationType.TICKET_ISSUED);
        n.setStatus(NotificationStatus.SENT);
        n.setSubject("Your ticket for Test Event");
        n.setBody("body text");
        return n;
    }

    private Notification aWaitlistPromotedNotification(UUID waitlistEntryId) {
        Notification n = new Notification();
        n.setWaitlistEntryId(waitlistEntryId);
        n.setUserId(UUID.randomUUID());
        n.setUserEmail("demo@ticketing.com");
        n.setType(NotificationType.WAITLIST_PROMOTED);
        n.setStatus(NotificationStatus.SENT);
        n.setSubject("A seat is available");
        n.setBody("body text");
        return n;
    }
}
