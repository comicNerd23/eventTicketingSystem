package com.ticketing.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class HoldSeatRequest {

    @NotNull
    private UUID eventId;

    @NotNull
    private UUID seatId;

    // Optional enrichment fields — avoids needing event-service for the demo slice
    private String eventTitle;
    private String seatLabel;
    private Double priceGbp;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public UUID getSeatId() { return seatId; }
    public void setSeatId(UUID seatId) { this.seatId = seatId; }
    public String getEventTitle() { return eventTitle; }
    public void setEventTitle(String eventTitle) { this.eventTitle = eventTitle; }
    public String getSeatLabel() { return seatLabel; }
    public void setSeatLabel(String seatLabel) { this.seatLabel = seatLabel; }
    public Double getPriceGbp() { return priceGbp; }
    public void setPriceGbp(Double priceGbp) { this.priceGbp = priceGbp; }
}
