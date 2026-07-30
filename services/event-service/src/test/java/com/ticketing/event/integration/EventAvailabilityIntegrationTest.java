package com.ticketing.event.integration;

import com.ticketing.event.client.BookingServiceClient;
import com.ticketing.event.client.EventSeatCount;
import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

/**
 * Proves the real bug fix end-to-end: Event.availableSeats used to be a stale snapshot
 * frozen at creation time. GET /events and GET /events/{id} must now reflect a real,
 * live-composed count from booking-service instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class EventAvailabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired TestRestTemplate restTemplate;
    @Autowired VenueRepository venueRepository;

    // No real booking-service runs in this test — stub the batch lookup, mirroring
    // SeatMapIntegrationTest's existing approach for the single-event lookup.
    @MockitoBean BookingServiceClient bookingServiceClient;

    private UUID venueId;

    @BeforeEach
    void setUp() {
        given(bookingServiceClient.getActiveSeats(any())).willReturn(List.of());

        Venue venue = new Venue();
        venue.setName("The O2 Arena");
        venue.setAddress("Peninsula Square");
        venue.setCity("London");
        venue.setCountry("UK");
        venue.setCapacity(20);

        Section section = new Section();
        section.setVenue(venue);
        section.setName("Floor");
        section.setRows(2);
        section.setSeatsPerRow(10);
        section.setPriceGbp(89.5);
        venue.setSections(List.of(section));

        venueId = venueRepository.save(venue).getId();
    }

    @Test
    void getEvent_realHttpRoundTrip_reflectsLiveActiveSeatCount() {
        UUID eventId = createEvent();

        given(bookingServiceClient.getActiveSeatCounts(List.of(eventId)))
            .willReturn(List.of(new EventSeatCount(eventId, 4)));

        ResponseEntity<EventResponse> resp = restTemplate.getForEntity("/events/{id}", EventResponse.class, eventId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getAvailableSeats()).isEqualTo(16);
    }

    @Test
    void listEvents_realHttpRoundTrip_reflectsLiveActiveSeatCountForEveryEventOnThePage() {
        UUID eventId = createEvent();

        // Other events may already exist on the page from earlier tests in this class (shared
        // Testcontainers Postgres, no cleanup between tests) — match on "this event is among the
        // ones requested" rather than an exact list, and only stub a count for our own event.
        given(bookingServiceClient.getActiveSeatCounts(argThat(ids -> ids != null && ids.contains(eventId))))
            .willReturn(List.of(new EventSeatCount(eventId, 1)));

        ResponseEntity<EventPageResponse> resp = restTemplate.getForEntity("/events", EventPageResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        EventResponse listed = resp.getBody().getContent().stream()
            .filter(e -> e.getId().equals(eventId))
            .findFirst()
            .orElseThrow();
        assertThat(listed.getAvailableSeats()).isEqualTo(19);
    }

    private UUID createEvent() {
        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Test Event");
        req.setCategory(EventCategory.CONCERT);
        req.setVenueId(venueId);
        req.setStartsAt(Instant.now().plusSeconds(3600));
        req.setEndsAt(Instant.now().plusSeconds(7200));

        ResponseEntity<EventResponse> createResp = restTemplate.postForEntity("/events", req, EventResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return createResp.getBody().getId();
    }
}
