package com.ticketing.event.service;

import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.EventStatus;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.exception.EventNotFoundException;
import com.ticketing.event.repository.EventRepository;
import com.ticketing.event.repository.EventSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final VenueService venueService;

    public EventService(EventRepository eventRepository, VenueService venueService) {
        this.eventRepository = eventRepository;
        this.venueService = venueService;
    }

    @Transactional
    public EventResponse createEvent(UUID organizerId, CreateEventRequest request) {
        Venue venue = venueService.getVenueEntity(request.getVenueId());

        Event event = new Event();
        event.setTitle(request.getTitle());
        event.setDescription(request.getDescription());
        event.setCategory(request.getCategory());
        event.setVenueId(venue.getId());
        event.setVenueName(venue.getName());
        event.setCity(venue.getCity());
        event.setStartsAt(request.getStartsAt());
        event.setEndsAt(request.getEndsAt());
        event.setStatus(EventStatus.PUBLISHED);
        event.setOrganizerId(organizerId);
        event.setTotalSeats(venue.getCapacity());
        event.setAvailableSeats(venue.getCapacity());
        event.setImageUrl(request.getImageUrl());

        event = eventRepository.save(event);
        log.info("Event created: id={} venue={}", event.getId(), venue.getId());
        return EventResponse.from(event);
    }

    public EventResponse getEvent(UUID eventId) {
        return eventRepository.findById(eventId)
            .map(EventResponse::from)
            .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    public EventPageResponse listEvents(String city, EventCategory category, LocalDate dateFrom, LocalDate dateTo, int page, int size) {
        var spec = EventSpecifications.withFilters(city, category, dateFrom, dateTo);
        var result = eventRepository.findAll(spec, PageRequest.of(page, size));
        return EventPageResponse.from(result);
    }
}
