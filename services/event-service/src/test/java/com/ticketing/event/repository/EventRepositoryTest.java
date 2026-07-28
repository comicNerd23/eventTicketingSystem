package com.ticketing.event.repository;

import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class EventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    EventRepository eventRepository;

    @Test
    void findAll_filteredByCity_returnsOnlyMatchingEvents() {
        eventRepository.save(anEvent("London", EventCategory.CONCERT, Instant.parse("2026-09-15T19:30:00Z")));
        eventRepository.save(anEvent("Manchester", EventCategory.CONCERT, Instant.parse("2026-09-20T19:30:00Z")));

        var spec = EventSpecifications.withFilters("London", null, null, null);
        Page<Event> result = eventRepository.findAll(spec, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getCity()).isEqualTo("London");
    }

    @Test
    void findAll_filteredByCategory_returnsOnlyMatchingEvents() {
        eventRepository.save(anEvent("London", EventCategory.CONCERT, Instant.parse("2026-09-15T19:30:00Z")));
        eventRepository.save(anEvent("London", EventCategory.THEATRE, Instant.parse("2026-09-20T19:30:00Z")));

        var spec = EventSpecifications.withFilters(null, EventCategory.THEATRE, null, null);
        Page<Event> result = eventRepository.findAll(spec, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo(EventCategory.THEATRE);
    }

    @Test
    void findAll_filteredByDateRange_excludesEventsOutsideRange() {
        eventRepository.save(anEvent("London", EventCategory.CONCERT, Instant.parse("2026-09-15T19:30:00Z")));
        eventRepository.save(anEvent("London", EventCategory.CONCERT, Instant.parse("2026-12-01T19:30:00Z")));

        var spec = EventSpecifications.withFilters(null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        Page<Event> result = eventRepository.findAll(spec, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findAll_noFilters_returnsAllEvents() {
        eventRepository.save(anEvent("London", EventCategory.CONCERT, Instant.parse("2026-09-15T19:30:00Z")));
        eventRepository.save(anEvent("Manchester", EventCategory.SPORTS, Instant.parse("2026-10-01T19:30:00Z")));

        var spec = EventSpecifications.withFilters(null, null, null, null);
        Page<Event> result = eventRepository.findAll(spec, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Event anEvent(String city, EventCategory category, Instant startsAt) {
        Event e = new Event();
        e.setTitle("Test Event");
        e.setCategory(category);
        e.setVenueId(UUID.randomUUID());
        e.setVenueName("Test Venue");
        e.setCity(city);
        e.setStartsAt(startsAt);
        e.setEndsAt(startsAt.plusSeconds(7200));
        e.setStatus(EventStatus.PUBLISHED);
        e.setOrganizerId(UUID.randomUUID());
        e.setTotalSeats(500);
        e.setAvailableSeats(500);
        return e;
    }
}
