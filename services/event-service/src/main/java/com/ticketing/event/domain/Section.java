package com.ticketing.event.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "sections")
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int rows;

    @Column(nullable = false)
    private int seatsPerRow;

    @Column(nullable = false)
    private Double priceGbp;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Venue getVenue() { return venue; }
    public void setVenue(Venue venue) { this.venue = venue; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getSeatsPerRow() { return seatsPerRow; }
    public void setSeatsPerRow(int seatsPerRow) { this.seatsPerRow = seatsPerRow; }
    public Double getPriceGbp() { return priceGbp; }
    public void setPriceGbp(Double priceGbp) { this.priceGbp = priceGbp; }
}
