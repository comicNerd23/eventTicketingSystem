package com.ticketing.booking.repository;

import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    boolean existsBySeatIdAndStatusIn(UUID seatId, List<BookingStatus> statuses);

    Optional<Booking> findBySeatIdAndStatus(UUID seatId, BookingStatus status);

    List<Booking> findByEventIdAndStatusIn(UUID eventId, List<BookingStatus> statuses);

    List<Booking> findByEventIdInAndStatusIn(List<UUID> eventIds, List<BookingStatus> statuses);
}
