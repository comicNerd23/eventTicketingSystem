package com.ticketing.waitlist.repository;

import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class WaitlistEntryRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    WaitlistEntryRepository waitlistEntryRepository;

    @Test
    void existsByEventIdAndUserIdAndStatus_whenWaiting_returnsTrue() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        waitlistEntryRepository.save(anEntry(eventId, userId, WaitlistStatus.WAITING));

        assertThat(waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitlistStatus.WAITING))
            .isTrue();
    }

    @Test
    void existsByEventIdAndUserIdAndStatus_whenAbsent_returnsFalse() {
        assertThat(waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(
            UUID.randomUUID(), UUID.randomUUID(), WaitlistStatus.WAITING)).isFalse();
    }

    @Test
    void findFirstByEventIdAndStatusOrderByJoinedAtAsc_returnsOldestWaitingEntry() {
        UUID eventId = UUID.randomUUID();
        WaitlistEntry older = anEntry(eventId, UUID.randomUUID(), WaitlistStatus.WAITING);
        older.setJoinedAt(Instant.now().minusSeconds(60));
        WaitlistEntry newer = anEntry(eventId, UUID.randomUUID(), WaitlistStatus.WAITING);
        newer.setJoinedAt(Instant.now());
        waitlistEntryRepository.save(newer);
        waitlistEntryRepository.save(older);

        Optional<WaitlistEntry> result = waitlistEntryRepository
            .findFirstByEventIdAndStatusOrderByJoinedAtAsc(eventId, WaitlistStatus.WAITING);

        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(older.getUserId());
    }

    @Test
    void findFirstByEventIdAndStatusOrderByJoinedAtAsc_whenNoneWaiting_returnsEmpty() {
        Optional<WaitlistEntry> result = waitlistEntryRepository
            .findFirstByEventIdAndStatusOrderByJoinedAtAsc(UUID.randomUUID(), WaitlistStatus.WAITING);

        assertThat(result).isEmpty();
    }

    @Test
    void countByEventIdAndStatusAndJoinedAtLessThan_countsOnlyEarlierWaitingEntries() {
        UUID eventId = UUID.randomUUID();
        WaitlistEntry first = anEntry(eventId, UUID.randomUUID(), WaitlistStatus.WAITING);
        first.setJoinedAt(Instant.now().minusSeconds(120));
        WaitlistEntry second = anEntry(eventId, UUID.randomUUID(), WaitlistStatus.WAITING);
        second.setJoinedAt(Instant.now().minusSeconds(60));
        waitlistEntryRepository.save(first);
        waitlistEntryRepository.save(second);

        long countBeforeSecond = waitlistEntryRepository.countByEventIdAndStatusAndJoinedAtLessThan(
            eventId, WaitlistStatus.WAITING, second.getJoinedAt());

        assertThat(countBeforeSecond).isEqualTo(1);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private WaitlistEntry anEntry(UUID eventId, UUID userId, WaitlistStatus status) {
        WaitlistEntry e = new WaitlistEntry();
        e.setEventId(eventId);
        e.setEventTitle("Test Event");
        e.setUserId(userId);
        e.setStatus(status);
        return e;
    }
}
