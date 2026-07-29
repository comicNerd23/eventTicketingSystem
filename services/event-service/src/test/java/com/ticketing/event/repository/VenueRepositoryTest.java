package com.ticketing.event.repository;

import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Venue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class VenueRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    VenueRepository venueRepository;

    @Test
    void save_venueWithSections_cascadesAndReloadsSections() {
        Venue venue = new Venue();
        venue.setName("The O2 Arena");
        venue.setAddress("Peninsula Square");
        venue.setCity("London");
        venue.setCountry("UK");
        venue.setCapacity(200);

        Section section = new Section();
        section.setVenue(venue);
        section.setName("Floor");
        section.setRows(10);
        section.setSeatsPerRow(20);
        section.setPriceGbp(89.5);
        venue.setSections(List.of(section));

        Venue saved = venueRepository.save(venue);

        Optional<Venue> found = venueRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getSections()).hasSize(1);
        assertThat(found.get().getSections().get(0).getName()).isEqualTo("Floor");
        assertThat(found.get().getCapacity()).isEqualTo(200);
    }
}
