package com.ticketing.event.dto;

import com.ticketing.event.domain.Venue;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class VenueResponse {

    private UUID id;
    private String name;
    private String address;
    private String city;
    private String country;
    private int capacity;
    private List<SectionResponse> sections;

    public static VenueResponse from(Venue v) {
        VenueResponse r = new VenueResponse();
        r.id = v.getId();
        r.name = v.getName();
        r.address = v.getAddress();
        r.city = v.getCity();
        r.country = v.getCountry();
        r.capacity = v.getCapacity();
        r.sections = v.getSections().stream().map(SectionResponse::from).collect(Collectors.toList());
        return r;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public int getCapacity() { return capacity; }
    public List<SectionResponse> getSections() { return sections; }
}
