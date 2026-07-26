package com.ticketing.booking.repository;

import com.ticketing.booking.domain.Booking;
import com.ticketing.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BookingRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    BookingRepository bookingRepository;

    @Test
    void existsBySeatIdAndStatusIn_whenConfirmedBookingExists_returnsTrue() {
        UUID seatId = UUID.randomUUID();
        bookingRepository.save(aBooking(seatId, BookingStatus.CONFIRMED));

        boolean exists = bookingRepository.existsBySeatIdAndStatusIn(
            seatId, List.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.HELD));

        assertThat(exists).isTrue();
    }

    @Test
    void existsBySeatIdAndStatusIn_whenOnlyCancelledBookingExists_returnsFalse() {
        // A cancelled booking means the seat is free again — a new hold should be allowed
        UUID seatId = UUID.randomUUID();
        bookingRepository.save(aBooking(seatId, BookingStatus.CANCELLED));

        boolean exists = bookingRepository.existsBySeatIdAndStatusIn(
            seatId, List.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.HELD));

        assertThat(exists).isFalse();
    }

    @Test
    void existsBySeatIdAndStatusIn_whenNoBookingForSeat_returnsFalse() {
        UUID seatId = UUID.randomUUID();

        boolean exists = bookingRepository.existsBySeatIdAndStatusIn(
            seatId, List.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.HELD));

        assertThat(exists).isFalse();
    }

    @Test
    void existsBySeatIdAndStatusIn_whenHeldBookingExistsForDifferentSeat_returnsFalse() {
        UUID seatIdA = UUID.randomUUID();
        UUID seatIdB = UUID.randomUUID();
        bookingRepository.save(aBooking(seatIdA, BookingStatus.HELD));

        // seatIdB should not be affected by seatIdA's booking
        boolean exists = bookingRepository.existsBySeatIdAndStatusIn(
            seatIdB, List.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.HELD));

        assertThat(exists).isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Booking aBooking(UUID seatId, BookingStatus status) {
        Booking b = new Booking();
        b.setEventId(UUID.randomUUID());
        b.setEventTitle("Test Event");
        b.setSeatId(seatId);
        b.setSeatLabel("A1");
        b.setUserId(UUID.randomUUID());
        b.setStatus(status);
        b.setTotalAmountGbp(50.0);
        b.setHoldExpiresAt(Instant.now().plusSeconds(600));
        return b;
    }
}
