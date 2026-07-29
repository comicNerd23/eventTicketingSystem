package com.ticketing.event.dto;

import com.ticketing.event.domain.Section;
import java.util.UUID;

public class SectionResponse {

    private UUID id;
    private String name;
    private int rows;
    private int seatsPerRow;
    private Double priceGbp;

    public static SectionResponse from(Section s) {
        SectionResponse r = new SectionResponse();
        r.id = s.getId();
        r.name = s.getName();
        r.rows = s.getRows();
        r.seatsPerRow = s.getSeatsPerRow();
        r.priceGbp = s.getPriceGbp();
        return r;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getRows() { return rows; }
    public int getSeatsPerRow() { return seatsPerRow; }
    public Double getPriceGbp() { return priceGbp; }
}
