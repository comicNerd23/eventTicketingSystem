package com.ticketing.event.dto;

import com.ticketing.event.domain.Seat;
import com.ticketing.event.domain.SeatStatus;

import java.util.UUID;

public class SeatResponse {

    private UUID id;
    private UUID sectionId;
    private String sectionName;
    private int rowNumber;
    private int seatNumber;
    private String label;
    private Double priceGbp;
    private SeatStatus status;

    public static SeatResponse from(Seat s, SeatStatus status) {
        SeatResponse r = new SeatResponse();
        r.id = s.getId();
        r.sectionId = s.getSectionId();
        r.sectionName = s.getSectionName();
        r.rowNumber = s.getRowNumber();
        r.seatNumber = s.getSeatNumber();
        r.label = s.getLabel();
        r.priceGbp = s.getPriceGbp();
        r.status = status;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getSectionId() { return sectionId; }
    public String getSectionName() { return sectionName; }
    public int getRowNumber() { return rowNumber; }
    public int getSeatNumber() { return seatNumber; }
    public String getLabel() { return label; }
    public Double getPriceGbp() { return priceGbp; }
    public SeatStatus getStatus() { return status; }
}
