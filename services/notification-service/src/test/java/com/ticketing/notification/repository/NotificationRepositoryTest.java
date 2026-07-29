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
    void findByBookingId_whenExists_returnsNotification() {
        UUID bookingId = UUID.randomUUID();
        notificationRepository.save(aNotification(bookingId));

        Optional<Notification> result = notificationRepository.findByBookingId(bookingId);

        assertThat(result).isPresent();
        assertThat(result.get().getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void findByBookingId_whenAbsent_returnsEmpty() {
        Optional<Notification> result = notificationRepository.findByBookingId(UUID.randomUUID());

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
}
