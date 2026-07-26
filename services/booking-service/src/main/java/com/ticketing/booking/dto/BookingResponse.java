package com.ticketing.booking.dto;

import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public class BookingResponse {

    private UUID id;
    private UUID eventId;
    private String eventTitle;
    private UUID seatId;
    private String seatLabel;
    private UUID userId;
    private BookingStatus status;
    private Double totalAmountGbp;
    private Instant holdExpiresAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String ticketReference;
    private Instant createdAt;

    public static BookingResponse from(Booking b) {
        BookingResponse r = new BookingResponse();
        r.id = b.getId();
        r.eventId = b.getEventId();
        r.eventTitle = b.getEventTitle();
        r.seatId = b.getSeatId();
        r.seatLabel = b.getSeatLabel();
        r.userId = b.getUserId();
        r.status = b.getStatus();
        r.totalAmountGbp = b.getTotalAmountGbp();
        r.holdExpiresAt = b.getHoldExpiresAt();
        r.confirmedAt = b.getConfirmedAt();
        r.cancelledAt = b.getCancelledAt();
        r.ticketReference = b.getTicketReference();
        r.createdAt = b.getCreatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventTitle() { return eventTitle; }
    public UUID getSeatId() { return seatId; }
    public String getSeatLabel() { return seatLabel; }
    public UUID getUserId() { return userId; }
    public BookingStatus getStatus() { return status; }
    public Double getTotalAmountGbp() { return totalAmountGbp; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getTicketReference() { return ticketReference; }
    public Instant getCreatedAt() { return createdAt; }
}
