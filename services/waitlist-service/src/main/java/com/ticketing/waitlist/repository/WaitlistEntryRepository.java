package com.ticketing.waitlist.repository;

import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    boolean existsByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, WaitlistStatus status);

    Optional<WaitlistEntry> findByEventIdAndUserId(UUID eventId, UUID userId);

    Optional<WaitlistEntry> findFirstByEventIdAndStatusOrderByJoinedAtAsc(UUID eventId, WaitlistStatus status);

    long countByEventIdAndStatusAndJoinedAtLessThan(UUID eventId, WaitlistStatus status, Instant joinedAt);
}
