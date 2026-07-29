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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final EventServiceClient eventServiceClient;
    private final WaitlistEventPublisher eventPublisher;
    private final long offerTtlMinutes;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                            EventServiceClient eventServiceClient,
                            WaitlistEventPublisher eventPublisher,
                            @Value("${waitlist.offer.ttl-minutes:15}") long offerTtlMinutes) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.eventServiceClient = eventServiceClient;
        this.eventPublisher = eventPublisher;
        this.offerTtlMinutes = offerTtlMinutes;
    }

    @Transactional
    public WaitlistEntryResponse join(UUID eventId, UUID userId) {
        if (waitlistEntryRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitlistStatus.WAITING)) {
            throw new AlreadyOnWaitlistException(eventId, userId);
        }

        EventInfo event = eventServiceClient.getEvent(eventId);

        WaitlistEntry entry = new WaitlistEntry();
        entry.setEventId(event.id());
        entry.setEventTitle(event.title());
        entry.setUserId(userId);
        entry.setStatus(WaitlistStatus.WAITING);
        entry = waitlistEntryRepository.save(entry);

        log.info("User {} joined waitlist for event {}: entry={}", userId, eventId, entry.getId());
        return toResponse(entry);
    }

    public WaitlistEntryResponse getEntry(UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
            .orElseThrow(() -> new WaitlistEntryNotFoundException(entryId));
        return toResponse(entry);
    }

    public WaitlistEntryResponse getMyEntryForEvent(UUID eventId, UUID userId) {
        WaitlistEntry entry = waitlistEntryRepository.findByEventIdAndUserId(eventId, userId)
            .orElseThrow(() -> new WaitlistEntryNotFoundException(eventId));
        return toResponse(entry);
    }

    @Transactional
    public void leave(UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
            .orElseThrow(() -> new WaitlistEntryNotFoundException(entryId));

        entry.setStatus(WaitlistStatus.LEFT);
        waitlistEntryRepository.save(entry);
        log.info("Waitlist entry left: entry={}", entryId);
    }

    @Transactional
    public void promoteNextForEvent(UUID eventId) {
        waitlistEntryRepository.findFirstByEventIdAndStatusOrderByJoinedAtAsc(eventId, WaitlistStatus.WAITING)
            .ifPresentOrElse(entry -> {
                entry.setStatus(WaitlistStatus.PROMOTED);
                entry.setPromotedAt(Instant.now());
                entry.setOfferExpiresAt(Instant.now().plus(Duration.ofMinutes(offerTtlMinutes)));
                waitlistEntryRepository.save(entry);

                eventPublisher.publishWaitlistPromoted(entry);
                log.info("Promoted waitlist entry {} for event {}", entry.getId(), eventId);
            }, () -> log.debug("No one waiting for event {} — nothing to promote", eventId));
    }

    private WaitlistEntryResponse toResponse(WaitlistEntry entry) {
        long position = waitlistEntryRepository.countByEventIdAndStatusAndJoinedAtLessThan(
            entry.getEventId(), WaitlistStatus.WAITING, entry.getJoinedAt()) + 1;
        return WaitlistEntryResponse.from(entry, position);
    }
}
