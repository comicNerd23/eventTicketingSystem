package com.ticketing.notification.repository;

import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findFirstByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    Optional<Notification> findByWaitlistEntryId(UUID waitlistEntryId);

    boolean existsByBookingIdAndType(UUID bookingId, NotificationType type);

    boolean existsByWaitlistEntryIdAndType(UUID waitlistEntryId, NotificationType type);
}
