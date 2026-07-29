package com.ticketing.event.service;

import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateVenueRequest;
import com.ticketing.event.dto.VenueResponse;
import com.ticketing.event.exception.VenueNotFoundException;
import com.ticketing.event.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VenueService {

    private static final Logger log = LoggerFactory.getLogger(VenueService.class);

    private final VenueRepository venueRepository;

    public VenueService(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    @Transactional
    public VenueResponse createVenue(CreateVenueRequest request) {
        Venue venue = new Venue();
        venue.setName(request.getName());
        venue.setAddress(request.getAddress());
        venue.setCity(request.getCity());
        venue.setCountry(request.getCountry());

        List<Section> sections = request.getSections().stream().map(sr -> {
            Section section = new Section();
            section.setVenue(venue);
            section.setName(sr.getName());
            section.setRows(sr.getRows());
            section.setSeatsPerRow(sr.getSeatsPerRow());
            section.setPriceGbp(sr.getPriceGbp());
            return section;
        }).collect(Collectors.toList());

        int capacity = sections.stream().mapToInt(s -> s.getRows() * s.getSeatsPerRow()).sum();
        venue.setCapacity(capacity);
        venue.setSections(sections);

        Venue saved = venueRepository.save(venue);
        log.info("Venue created: id={} capacity={}", saved.getId(), capacity);
        return VenueResponse.from(saved);
    }

    Venue getVenueEntity(UUID venueId) {
        return venueRepository.findById(venueId)
            .orElseThrow(() -> new VenueNotFoundException(venueId));
    }
}
