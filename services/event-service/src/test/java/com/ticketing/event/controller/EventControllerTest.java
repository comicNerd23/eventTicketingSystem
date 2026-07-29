package com.ticketing.event.controller;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.EventStatus;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.SeatStatus;
import com.ticketing.event.exception.EventNotFoundException;
import com.ticketing.event.exception.VenueNotFoundException;
import com.ticketing.event.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean EventService eventService;

    // ── POST /events ──────────────────────────────────────────────────────────

    @Test
    void createEvent_validRequest_returns201WithPublishedEvent() throws Exception {
        UUID venueId = UUID.randomUUID();
        given(eventService.createEvent(any(), any())).willReturn(EventResponse.from(anEvent(venueId)));

        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Coldplay: Music of the Spheres Tour");
        req.setCategory(EventCategory.CONCERT);
        req.setVenueId(venueId);
        req.setStartsAt(Instant.parse("2026-09-15T19:30:00Z"));
        req.setEndsAt(Instant.parse("2026-09-15T22:30:00Z"));

        mvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.venueId").value(venueId.toString()))
            .andExpect(jsonPath("$.availableSeats").value(650));
    }

    @Test
    void createEvent_missingRequiredFields_returns400() throws Exception {
        mvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createEvent_whenVenueNotFound_returns404() throws Exception {
        UUID venueId = UUID.randomUUID();
        given(eventService.createEvent(any(), any())).willThrow(new VenueNotFoundException(venueId));

        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Coldplay: Music of the Spheres Tour");
        req.setCategory(EventCategory.CONCERT);
        req.setVenueId(venueId);
        req.setStartsAt(Instant.parse("2026-09-15T19:30:00Z"));
        req.setEndsAt(Instant.parse("2026-09-15T22:30:00Z"));

        mvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(venueId.toString())));
    }

    // ── GET /events/{id} ──────────────────────────────────────────────────────

    @Test
    void getEvent_whenExists_returns200() throws Exception {
        UUID venueId = UUID.randomUUID();
        Event event = anEvent(venueId);
        given(eventService.getEvent(event.getId())).willReturn(EventResponse.from(event));

        mvc.perform(get("/events/{id}", event.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Coldplay: Music of the Spheres Tour"));
    }

    @Test
    void getEvent_whenNotFound_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(eventService.getEvent(eventId)).willThrow(new EventNotFoundException(eventId));

        mvc.perform(get("/events/{id}", eventId))
            .andExpect(status().isNotFound());
    }

    // ── GET /events/{id}/seats ───────────────────────────────────────────────

    @Test
    void getSeatMap_whenExists_returns200WithSeats() throws Exception {
        UUID eventId = UUID.randomUUID();
        Seat seat = new Seat();
        seat.setId(UUID.randomUUID());
        seat.setSectionName("Floor");
        seat.setRowNumber(1);
        seat.setSeatNumber(1);
        seat.setLabel("Floor-R1-S1");
        seat.setPriceGbp(89.5);
        given(eventService.getSeatMap(eventId)).willReturn(List.of(SeatResponse.from(seat, SeatStatus.AVAILABLE)));

        mvc.perform(get("/events/{id}/seats", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].label").value("Floor-R1-S1"))
            .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    void getSeatMap_whenEventNotFound_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(eventService.getSeatMap(eventId)).willThrow(new EventNotFoundException(eventId));

        mvc.perform(get("/events/{id}/seats", eventId))
            .andExpect(status().isNotFound());
    }

    // ── GET /events/{id}/seats/{seatId} ──────────────────────────────────────

    @Test
    void getSeat_whenExists_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        Seat seat = new Seat();
        seat.setId(UUID.randomUUID());
        seat.setSectionName("Floor");
        seat.setRowNumber(1);
        seat.setSeatNumber(1);
        seat.setLabel("Floor-R1-S1");
        seat.setPriceGbp(89.5);
        given(eventService.getSeat(eventId, seat.getId())).willReturn(SeatResponse.from(seat, SeatStatus.AVAILABLE));

        mvc.perform(get("/events/{id}/seats/{seatId}", eventId, seat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Floor-R1-S1"))
            .andExpect(jsonPath("$.priceGbp").value(89.5));
    }

    @Test
    void getSeat_whenNotFound_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        given(eventService.getSeat(eventId, seatId))
            .willThrow(new com.ticketing.event.exception.SeatNotFoundException(eventId, seatId));

        mvc.perform(get("/events/{id}/seats/{seatId}", eventId, seatId))
            .andExpect(status().isNotFound());
    }

    // ── GET /events ───────────────────────────────────────────────────────────

    @Test
    void listEvents_returns200WithPage() throws Exception {
        Event event = anEvent(UUID.randomUUID());
        var page = new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1);
        given(eventService.listEvents(any(), any(), any(), any(), anyInt(), anyInt()))
            .willReturn(EventPageResponse.from(page));

        mvc.perform(get("/events").param("city", "London"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].city").value("London"))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Event anEvent(UUID venueId) {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setTitle("Coldplay: Music of the Spheres Tour");
        e.setCategory(EventCategory.CONCERT);
        e.setVenueId(venueId);
        e.setVenueName("The O2 Arena");
        e.setCity("London");
        e.setStartsAt(Instant.parse("2026-09-15T19:30:00Z"));
        e.setEndsAt(Instant.parse("2026-09-15T22:30:00Z"));
        e.setStatus(EventStatus.PUBLISHED);
        e.setOrganizerId(UUID.randomUUID());
        e.setTotalSeats(650);
        e.setAvailableSeats(650);
        return e;
    }
}
