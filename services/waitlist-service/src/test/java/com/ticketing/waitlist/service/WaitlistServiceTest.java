package com.ticketing.waitlist.service;

import com.ticketing.waitlist.client.EventInfo;
import com.ticketing.waitlist.client.EventServiceClient;
import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;
import com.ticketing.waitlist.dto.WaitlistEntryResponse;
import com.ticketing.waitlist.exception.AlreadyOnWaitlistException;
import com.ticketing.waitlist.exception.WaitlistEntryNotFoundException;
import com.ticketing.waitlist.kafka.producer.WaitlistEventPublisher;
import com.ticketing.waitlist.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock WaitlistEntryRepository waitlistEntryRepository;
    @Mock EventServiceClient eventServiceClient;
    @Mock WaitlistEventPublisher eventPublisher;

    WaitlistService waitlistService;

    private UUID eventId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        userId = UUID.randomUUID();
        waitlistService = new WaitlistService(waitlistEntryRepository, eventServiceClient, eventPublisher, 15);
    }

    // ── join ──────────────────────────────────────────────────────────────────

    @Test
    void join_whenNotAlreadyWaiting_createsWaitingEntry() {
        given(waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitlistStatus.WAITING))
            .willReturn(false);
        given(eventServiceClient.getEvent(eventId)).willReturn(new EventInfo(eventId, "Test Event"));
        given(waitlistEntryRepository.save(any())).willAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(waitlistEntryRepository.countByEventIdAndStatusAndJoinedAtLessThan(any(), any(), any())).willReturn(0L);

        WaitlistEntryResponse response = waitlistService.join(eventId, userId);

        assertThat(response.getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(response.getEventTitle()).isEqualTo("Test Event");
        assertThat(response.getPosition()).isEqualTo(1);
    }

    @Test
    void join_whenAlreadyWaiting_throwsAlreadyOnWaitlist() {
        given(waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitlistStatus.WAITING))
            .willReturn(true);

        assertThatThrownBy(() -> waitlistService.join(eventId, userId))
            .isInstanceOf(AlreadyOnWaitlistException.class);

        then(eventServiceClient).shouldHaveNoInteractions();
        then(waitlistEntryRepository).should(never()).save(any());
    }

    // ── getEntry ──────────────────────────────────────────────────────────────

    @Test
    void getEntry_whenExists_returnsResponse() {
        UUID entryId = UUID.randomUUID();
        WaitlistEntry entry = anEntry(entryId, eventId, userId, WaitlistStatus.WAITING);
        given(waitlistEntryRepository.findById(entryId)).willReturn(Optional.of(entry));
        given(waitlistEntryRepository.countByEventIdAndStatusAndJoinedAtLessThan(any(), any(), any())).willReturn(2L);

        WaitlistEntryResponse response = waitlistService.getEntry(entryId);

        assertThat(response.getId()).isEqualTo(entryId);
        assertThat(response.getPosition()).isEqualTo(3);
    }

    @Test
    void getEntry_whenNotFound_throwsWaitlistEntryNotFound() {
        UUID entryId = UUID.randomUUID();
        given(waitlistEntryRepository.findById(entryId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> waitlistService.getEntry(entryId))
            .isInstanceOf(WaitlistEntryNotFoundException.class);
    }

    // ── leave ─────────────────────────────────────────────────────────────────

    @Test
    void leave_setsStatusToLeft() {
        UUID entryId = UUID.randomUUID();
        WaitlistEntry entry = anEntry(entryId, eventId, userId, WaitlistStatus.WAITING);
        given(waitlistEntryRepository.findById(entryId)).willReturn(Optional.of(entry));

        waitlistService.leave(entryId);

        ArgumentCaptor<WaitlistEntry> saved = ArgumentCaptor.forClass(WaitlistEntry.class);
        then(waitlistEntryRepository).should().save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WaitlistStatus.LEFT);
    }

    @Test
    void leave_whenNotFound_throwsWaitlistEntryNotFound() {
        UUID entryId = UUID.randomUUID();
        given(waitlistEntryRepository.findById(entryId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> waitlistService.leave(entryId))
            .isInstanceOf(WaitlistEntryNotFoundException.class);
    }

    // ── promoteNextForEvent ───────────────────────────────────────────────────

    @Test
    void promoteNextForEvent_whenSomeoneWaiting_promotesOldestAndPublishes() {
        WaitlistEntry entry = anEntry(UUID.randomUUID(), eventId, userId, WaitlistStatus.WAITING);
        given(waitlistEntryRepository.findFirstByEventIdAndStatusOrderByJoinedAtAsc(eventId, WaitlistStatus.WAITING))
            .willReturn(Optional.of(entry));
        given(waitlistEntryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        waitlistService.promoteNextForEvent(eventId);

        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(entry.getPromotedAt()).isNotNull();
        assertThat(entry.getOfferExpiresAt()).isAfter(Instant.now());
        then(eventPublisher).should().publishWaitlistPromoted(entry);
    }

    @Test
    void promoteNextForEvent_whenNoOneWaiting_isNoOp() {
        given(waitlistEntryRepository.findFirstByEventIdAndStatusOrderByJoinedAtAsc(eventId, WaitlistStatus.WAITING))
            .willReturn(Optional.empty());

        waitlistService.promoteNextForEvent(eventId);

        then(waitlistEntryRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private WaitlistEntry anEntry(UUID id, UUID eventId, UUID userId, WaitlistStatus status) {
        WaitlistEntry e = new WaitlistEntry();
        e.setId(id);
        e.setEventId(eventId);
        e.setEventTitle("Test Event");
        e.setUserId(userId);
        e.setStatus(status);
        return e;
    }
}
