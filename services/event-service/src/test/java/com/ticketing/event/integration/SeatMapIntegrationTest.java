package com.ticketing.event.integration;

import com.ticketing.event.client.ActiveSeat;
import com.ticketing.event.client.BookingServiceClient;
import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
import com.ticketing.event.repository.SeatRepository;
import com.ticketing.event.repository.VenueRepository;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.Section;
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
import static org.mockito.BDDMockito.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class SeatMapIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired TestRestTemplate restTemplate;
    @Autowired VenueRepository venueRepository;
    @Autowired SeatRepository seatRepository;

    // No real booking-service runs in this test — stub the active-seats lookup instead,
    // mirroring how booking-service's own integration test stubs EventServiceClient.
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
    void seatMap_realHttpRoundTrip_composesStatusFromBookingService() {
        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Test Event");
        req.setCategory(EventCategory.CONCERT);
        req.setVenueId(venueId);
        req.setStartsAt(Instant.now().plusSeconds(3600));
        req.setEndsAt(Instant.now().plusSeconds(7200));

        ResponseEntity<EventResponse> createResp = restTemplate.postForEntity("/events", req, EventResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID eventId = createResp.getBody().getId();

        List<Seat> generatedSeats = seatRepository.findByEventId(eventId);
        assertThat(generatedSeats).hasSize(20);

        UUID heldSeatId = generatedSeats.get(0).getId();
        given(bookingServiceClient.getActiveSeats(eventId))
            .willReturn(List.of(new ActiveSeat(heldSeatId, "HELD")));

        ResponseEntity<SeatResponse[]> seatMapResp = restTemplate.getForEntity(
            "/events/{eventId}/seats", SeatResponse[].class, eventId);

        assertThat(seatMapResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SeatResponse> seats = List.of(seatMapResp.getBody());
        assertThat(seats).hasSize(20);
        assertThat(seats.stream().filter(s -> s.getId().equals(heldSeatId)).findFirst().orElseThrow().getStatus())
            .isEqualTo(com.ticketing.event.domain.SeatStatus.HELD);
        assertThat(seats.stream().filter(s -> !s.getId().equals(heldSeatId)))
            .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(com.ticketing.event.domain.SeatStatus.AVAILABLE));
    }

    @Test
    void seatMap_whenEventDoesNotExist_returns404() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
            "/events/{eventId}/seats", String.class, UUID.randomUUID());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
