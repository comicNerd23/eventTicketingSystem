package com.ticketing.event.service;

import com.ticketing.event.client.ActiveSeat;
import com.ticketing.event.client.BookingServiceClient;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.EventStatus;
import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.SeatStatus;
import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
import com.ticketing.event.exception.EventNotFoundException;
import com.ticketing.event.exception.SeatNotFoundException;
import com.ticketing.event.repository.EventRepository;
import com.ticketing.event.repository.EventSpecifications;
import com.ticketing.event.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final VenueService venueService;
    private final SeatRepository seatRepository;
    private final BookingServiceClient bookingServiceClient;

    public EventService(EventRepository eventRepository, VenueService venueService,
                         SeatRepository seatRepository, BookingServiceClient bookingServiceClient) {
        this.eventRepository = eventRepository;
        this.venueService = venueService;
        this.seatRepository = seatRepository;
        this.bookingServiceClient = bookingServiceClient;
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
        generateSeats(event.getId(), venue);
        log.info("Event created: id={} venue={}", event.getId(), venue.getId());
        return EventResponse.from(event);
    }

    private void generateSeats(UUID eventId, Venue venue) {
        List<Seat> seats = new ArrayList<>();
        for (Section section : venue.getSections()) {
            for (int row = 1; row <= section.getRows(); row++) {
                for (int seatNum = 1; seatNum <= section.getSeatsPerRow(); seatNum++) {
                    Seat seat = new Seat();
                    seat.setEventId(eventId);
                    seat.setSectionId(section.getId());
                    seat.setSectionName(section.getName());
                    seat.setRowNumber(row);
                    seat.setSeatNumber(seatNum);
                    seat.setLabel(section.getName() + "-R" + row + "-S" + seatNum);
                    seat.setPriceGbp(section.getPriceGbp());
                    seats.add(seat);
                }
            }
        }
        seatRepository.saveAll(seats);
        log.info("Generated {} seats for event {}", seats.size(), eventId);
    }

    public List<SeatResponse> getSeatMap(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        List<Seat> seats = seatRepository.findByEventId(eventId);
        Map<UUID, String> activeStatusBySeatId = bookingServiceClient.getActiveSeats(eventId).stream()
            .collect(java.util.stream.Collectors.toMap(ActiveSeat::seatId, ActiveSeat::status));

        return seats.stream()
            .map(seat -> SeatResponse.from(seat, resolveStatus(activeStatusBySeatId.get(seat.getId()))))
            .toList();
    }

    public SeatResponse getSeat(UUID eventId, UUID seatId) {
        Seat seat = seatRepository.findByEventIdAndId(eventId, seatId)
            .orElseThrow(() -> new SeatNotFoundException(eventId, seatId));
        // Pure inventory lookup — the caller (booking-service) already knows whether
        // a seat is currently held via its own DB, so status here is a placeholder.
        return SeatResponse.from(seat, SeatStatus.AVAILABLE);
    }

    private SeatStatus resolveStatus(String bookingStatus) {
        if (bookingStatus == null) {
            return SeatStatus.AVAILABLE;
        }
        return switch (bookingStatus) {
            case "CONFIRMED" -> SeatStatus.BOOKED;
            case "HELD", "PAYMENT_PENDING" -> SeatStatus.HELD;
            default -> SeatStatus.AVAILABLE;
        };
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
