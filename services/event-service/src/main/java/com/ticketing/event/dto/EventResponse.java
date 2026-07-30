package com.ticketing.event.dto;

import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import com.ticketing.event.domain.EventStatus;

import java.time.Instant;
import java.util.UUID;

public class EventResponse {

    private UUID id;
    private String title;
    private String description;
    private EventCategory category;
    private UUID venueId;
    private String venueName;
    private String city;
    private Instant startsAt;
    private Instant endsAt;
    private EventStatus status;
    private UUID organizerId;
    private int totalSeats;
    private int availableSeats;
    private String imageUrl;
    private Instant createdAt;

    public static EventResponse from(Event e) {
        EventResponse r = new EventResponse();
        r.id = e.getId();
        r.title = e.getTitle();
        r.description = e.getDescription();
        r.category = e.getCategory();
        r.venueId = e.getVenueId();
        r.venueName = e.getVenueName();
        r.city = e.getCity();
        r.startsAt = e.getStartsAt();
        r.endsAt = e.getEndsAt();
        r.status = e.getStatus();
        r.organizerId = e.getOrganizerId();
        r.totalSeats = e.getTotalSeats();
        r.availableSeats = e.getAvailableSeats();
        r.imageUrl = e.getImageUrl();
        r.createdAt = e.getCreatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public EventCategory getCategory() { return category; }
    public UUID getVenueId() { return venueId; }
    public String getVenueName() { return venueName; }
    public String getCity() { return city; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public EventStatus getStatus() { return status; }
    public UUID getOrganizerId() { return organizerId; }
    public int getTotalSeats() { return totalSeats; }
    public int getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(int availableSeats) { this.availableSeats = availableSeats; }
    public String getImageUrl() { return imageUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
