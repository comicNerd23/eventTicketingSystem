package com.ticketing.event.controller;

import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.dto.CreateEventRequest;
import com.ticketing.event.dto.EventPageResponse;
import com.ticketing.event.dto.EventResponse;
import com.ticketing.event.dto.SeatResponse;
import com.ticketing.event.service.EventService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @RequestHeader(value = "X-Organizer-Id", defaultValue = "00000000-0000-0000-0000-000000000099") UUID organizerId,
            @Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.status(201).body(eventService.createEvent(organizerId, request));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @GetMapping("/{eventId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeatMap(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getSeatMap(eventId));
    }

    @GetMapping
    public ResponseEntity<EventPageResponse> listEvents(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(eventService.listEvents(city, category, dateFrom, dateTo, page, size));
    }
}
