package com.ticketing.event.repository;

import com.ticketing.event.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByEventId(UUID eventId);

    Optional<Seat> findByEventIdAndId(UUID eventId, UUID id);
}
