package com.ticketing.event.repository;

import com.ticketing.event.domain.Seat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
class SeatRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    SeatRepository seatRepository;

    @Test
    void findByEventId_returnsOnlySeatsForThatEvent() {
        UUID eventId = UUID.randomUUID();
        seatRepository.save(aSeat(eventId, "Floor-R1-S1"));
        seatRepository.save(aSeat(eventId, "Floor-R1-S2"));
        seatRepository.save(aSeat(UUID.randomUUID(), "Floor-R1-S1"));

        List<Seat> result = seatRepository.findByEventId(eventId);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(s -> assertThat(s.getEventId()).isEqualTo(eventId));
    }

    @Test
    void findByEventId_whenNoneExist_returnsEmpty() {
        assertThat(seatRepository.findByEventId(UUID.randomUUID())).isEmpty();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Seat aSeat(UUID eventId, String label) {
        Seat s = new Seat();
        s.setEventId(eventId);
        s.setSectionId(UUID.randomUUID());
        s.setSectionName("Floor");
        s.setRowNumber(1);
        s.setSeatNumber(1);
        s.setLabel(label);
        s.setPriceGbp(89.5);
        return s;
    }
}
