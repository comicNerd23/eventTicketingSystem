package com.ticketing.event.service;

import com.ticketing.event.client.ActiveSeat;
import com.ticketing.event.client.BookingServiceClient;
import com.ticketing.event.client.EventSeatCount;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.SeatStatus;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
import com.ticketing.event.exception.BookingServiceUnavailableException;
import com.ticketing.event.exception.EventNotFoundException;
import com.ticketing.event.repository.EventRepository;
import com.ticketing.event.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock EventRepository eventRepository;
    @Mock VenueService venueService;
    @Mock SeatRepository seatRepository;
    @Mock BookingServiceClient bookingServiceClient;

    @InjectMocks EventService eventService;

    private UUID venueId;
    private UUID organizerId;

    @BeforeEach
    void setUp() {
        venueId = UUID.randomUUID();
        organizerId = UUID.randomUUID();
    }

    // ── createEvent — seat generation ────────────────────────────────────────

    @Test
    void createEvent_generatesOneSeatPerRowTimesSeatsPerRowAcrossAllSections() {
        given(venueService.getVenueEntity(venueId)).willReturn(aVenue());
        given(eventRepository.save(any())).willAnswer(inv -> {
            var e = inv.getArgument(0, com.ticketing.event.domain.Event.class);
            e.setId(UUID.randomUUID());
            return e;
        });

        eventService.createEvent(organizerId, aCreateEventRequest());

        ArgumentCaptor<List<Seat>> captor = ArgumentCaptor.forClass(List.class);
        then(seatRepository).should().saveAll(captor.capture());
        // Floor 10x20 + Upper Tier 15x30 = 650
        assertThat(captor.getValue()).hasSize(650);
        assertThat(captor.getValue()).allSatisfy(seat -> assertThat(seat.getPriceGbp()).isNotNull());
    }

    @Test
    void createEvent_seatLabelsIncludeSectionRowAndSeatNumber() {
        given(venueService.getVenueEntity(venueId)).willReturn(aVenue());
        given(eventRepository.save(any())).willAnswer(inv -> {
            var e = inv.getArgument(0, com.ticketing.event.domain.Event.class);
            e.setId(UUID.randomUUID());
            return e;
        });

        eventService.createEvent(organizerId, aCreateEventRequest());

        ArgumentCaptor<List<Seat>> captor = ArgumentCaptor.forClass(List.class);
        then(seatRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(seat -> {
            assertThat(seat.getLabel()).isEqualTo("Floor-R1-S1");
            assertThat(seat.getPriceGbp()).isEqualTo(89.5);
        });
    }

    // ── getSeatMap ────────────────────────────────────────────────────────────

    @Test
    void getSeatMap_whenEventNotFound_throwsEventNotFoundException() {
        UUID eventId = UUID.randomUUID();
        given(eventRepository.existsById(eventId)).willReturn(false);

        assertThatThrownBy(() -> eventService.getSeatMap(eventId))
            .isInstanceOf(EventNotFoundException.class);

        then(seatRepository).shouldHaveNoInteractions();
    }

    @Test
    void getSeatMap_composesAvailableHeldAndBookedStatuses() {
        UUID eventId = UUID.randomUUID();
        Seat available = aSeat(eventId, "Floor-R1-S1");
        Seat held = aSeat(eventId, "Floor-R1-S2");
        Seat booked = aSeat(eventId, "Floor-R1-S3");

        given(eventRepository.existsById(eventId)).willReturn(true);
        given(seatRepository.findByEventId(eventId)).willReturn(List.of(available, held, booked));
        given(bookingServiceClient.getActiveSeats(eventId)).willReturn(List.of(
            new ActiveSeat(held.getId(), "HELD"),
            new ActiveSeat(booked.getId(), "CONFIRMED")
        ));

        List<SeatResponse> result = eventService.getSeatMap(eventId);

        assertThat(result).hasSize(3);
        assertThat(statusOf(result, available.getId())).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(statusOf(result, held.getId())).isEqualTo(SeatStatus.HELD);
        assertThat(statusOf(result, booked.getId())).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void getSeatMap_whenPaymentPending_mapsToHeld() {
        UUID eventId = UUID.randomUUID();
        Seat seat = aSeat(eventId, "Floor-R1-S1");

        given(eventRepository.existsById(eventId)).willReturn(true);
        given(seatRepository.findByEventId(eventId)).willReturn(List.of(seat));
        given(bookingServiceClient.getActiveSeats(eventId))
            .willReturn(List.of(new ActiveSeat(seat.getId(), "PAYMENT_PENDING")));

        List<SeatResponse> result = eventService.getSeatMap(eventId);

        assertThat(statusOf(result, seat.getId())).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void getSeatMap_whenNoActiveSeats_allAvailable() {
        UUID eventId = UUID.randomUUID();
        Seat seat = aSeat(eventId, "Floor-R1-S1");

        given(eventRepository.existsById(eventId)).willReturn(true);
        given(seatRepository.findByEventId(eventId)).willReturn(List.of(seat));
        given(bookingServiceClient.getActiveSeats(eventId)).willReturn(List.of());

        List<SeatResponse> result = eventService.getSeatMap(eventId);

        assertThat(statusOf(result, seat.getId())).isEqualTo(SeatStatus.AVAILABLE);
    }

    // ── getSeat ───────────────────────────────────────────────────────────────

    @Test
    void getSeat_whenExists_returnsSeatResponse() {
        UUID eventId = UUID.randomUUID();
        Seat seat = aSeat(eventId, "Floor-R1-S1");
        given(seatRepository.findByEventIdAndId(eventId, seat.getId())).willReturn(java.util.Optional.of(seat));

        SeatResponse response = eventService.getSeat(eventId, seat.getId());

        assertThat(response.getId()).isEqualTo(seat.getId());
        assertThat(response.getLabel()).isEqualTo("Floor-R1-S1");
        assertThat(response.getPriceGbp()).isEqualTo(89.5);
    }

    @Test
    void getSeat_whenNotFound_throwsSeatNotFoundException() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        given(seatRepository.findByEventIdAndId(eventId, seatId)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> eventService.getSeat(eventId, seatId))
            .isInstanceOf(com.ticketing.event.exception.SeatNotFoundException.class);
    }

    // ── getEvent — live availability composition ────────────────────────────────

    @Test
    void getEvent_composesLiveAvailableSeatsFromActiveBookingCount() {
        UUID eventId = UUID.randomUUID();
        given(eventRepository.findById(eventId)).willReturn(java.util.Optional.of(anEvent(eventId, 650)));
        given(bookingServiceClient.getActiveSeatCounts(List.of(eventId)))
            .willReturn(List.of(new EventSeatCount(eventId, 5)));

        EventResponse response = eventService.getEvent(eventId);

        assertThat(response.getAvailableSeats()).isEqualTo(645);
    }

    @Test
    void getEvent_whenNoActiveBookings_availableSeatsEqualsTotalSeats() {
        UUID eventId = UUID.randomUUID();
        given(eventRepository.findById(eventId)).willReturn(java.util.Optional.of(anEvent(eventId, 650)));
        given(bookingServiceClient.getActiveSeatCounts(List.of(eventId))).willReturn(List.of());

        EventResponse response = eventService.getEvent(eventId);

        assertThat(response.getAvailableSeats()).isEqualTo(650);
    }

    @Test
    void getEvent_whenBookingServiceUnavailable_propagatesException() {
        UUID eventId = UUID.randomUUID();
        given(eventRepository.findById(eventId)).willReturn(java.util.Optional.of(anEvent(eventId, 650)));
        given(bookingServiceClient.getActiveSeatCounts(List.of(eventId)))
            .willThrow(new BookingServiceUnavailableException(List.of(eventId), new RuntimeException("down")));

        assertThatThrownBy(() -> eventService.getEvent(eventId))
            .isInstanceOf(BookingServiceUnavailableException.class);
    }

    // ── listEvents — live availability composition ──────────────────────────────

    @Test
    void listEvents_composesLiveAvailableSeatsForEveryEventInThePage() {
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        PageImpl<Event> page = new PageImpl<>(List.of(anEvent(eventA, 650), anEvent(eventB, 100)));
        given(eventRepository.findAll(any(Specification.class), any(PageRequest.class))).willReturn(page);
        given(bookingServiceClient.getActiveSeatCounts(List.of(eventA, eventB)))
            .willReturn(List.of(new EventSeatCount(eventA, 3), new EventSeatCount(eventB, 0)));

        EventPageResponse result = eventService.listEvents(null, null, null, null, 0, 20);

        assertThat(availableSeatsOf(result, eventA)).isEqualTo(647);
        assertThat(availableSeatsOf(result, eventB)).isEqualTo(100);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Event anEvent(UUID id, int totalSeats) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("Coldplay: Music of the Spheres Tour");
        event.setVenueId(venueId);
        event.setVenueName("The O2 Arena");
        event.setCity("London");
        event.setStartsAt(Instant.parse("2026-09-15T19:30:00Z"));
        event.setEndsAt(Instant.parse("2026-09-15T22:30:00Z"));
        event.setOrganizerId(organizerId);
        event.setTotalSeats(totalSeats);
        event.setAvailableSeats(totalSeats);
        event.setCreatedAt(Instant.now());
        return event;
    }

    private int availableSeatsOf(EventPageResponse page, UUID eventId) {
        return page.getContent().stream()
            .filter(e -> e.getId().equals(eventId))
            .findFirst()
            .orElseThrow()
            .getAvailableSeats();
    }

    private SeatStatus statusOf(List<SeatResponse> seats, UUID seatId) {
        return seats.stream().filter(s -> s.getId().equals(seatId)).findFirst().orElseThrow().getStatus();
    }

    private Venue aVenue() {
        Venue venue = new Venue();
        venue.setId(venueId);
        venue.setName("The O2 Arena");
        venue.setCity("London");
        venue.setCapacity(650);

        Section floor = new Section();
        floor.setId(UUID.randomUUID());
        floor.setVenue(venue);
        floor.setName("Floor");
        floor.setRows(10);
        floor.setSeatsPerRow(20);
        floor.setPriceGbp(89.5);

        Section upperTier = new Section();
        upperTier.setId(UUID.randomUUID());
        upperTier.setVenue(venue);
        upperTier.setName("Upper Tier");
        upperTier.setRows(15);
        upperTier.setSeatsPerRow(30);
        upperTier.setPriceGbp(45.0);

        venue.setSections(List.of(floor, upperTier));
        return venue;
    }

    private CreateEventRequest aCreateEventRequest() {
        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Coldplay: Music of the Spheres Tour");
        req.setCategory(com.ticketing.event.domain.EventCategory.CONCERT);
        req.setVenueId(venueId);
        req.setStartsAt(Instant.parse("2026-09-15T19:30:00Z"));
        req.setEndsAt(Instant.parse("2026-09-15T22:30:00Z"));
        return req;
    }

    private Seat aSeat(UUID eventId, String label) {
        Seat s = new Seat();
        s.setId(UUID.randomUUID());
        s.setEventId(eventId);
        s.setSectionId(UUID.randomUUID());
        s.setSectionName("Floor");
        s.setRowNumber(1);
        s.setSeatNumber(1);
        s.setLabel(label);
        s.setPriceGbp(89.5);
        return s;
    }
}
