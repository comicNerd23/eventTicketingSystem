package com.ticketing.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateSectionRequest {

    @NotBlank
    private String name;

    @NotNull
    @Min(1)
    private Integer rows;

    @NotNull
    @Min(1)
    private Integer seatsPerRow;

    @NotNull
    @Positive
    private Double priceGbp;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getRows() { return rows; }
    public void setRows(Integer rows) { this.rows = rows; }
    public Integer getSeatsPerRow() { return seatsPerRow; }
    public void setSeatsPerRow(Integer seatsPerRow) { this.seatsPerRow = seatsPerRow; }
    public Double getPriceGbp() { return priceGbp; }
    public void setPriceGbp(Double priceGbp) { this.priceGbp = priceGbp; }
}
