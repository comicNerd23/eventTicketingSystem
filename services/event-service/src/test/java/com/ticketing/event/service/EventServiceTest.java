package com.ticketing.event.service;

import com.ticketing.event.client.ActiveSeat;
import com.ticketing.event.client.BookingServiceClient;
import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.SeatStatus;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
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

    // ── helpers ───────────────────────────────────────────────────────────────

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
