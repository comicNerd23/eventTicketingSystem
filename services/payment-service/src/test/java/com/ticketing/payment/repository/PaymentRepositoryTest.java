package com.ticketing.payment.repository;

import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class PaymentRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    PaymentRepository paymentRepository;

    @Test
    void findByBookingId_whenExists_returnsPayment() {
        UUID bookingId = UUID.randomUUID();
        paymentRepository.save(aPayment(bookingId, "pi_test_1"));

        Optional<Payment> result = paymentRepository.findByBookingId(bookingId);

        assertThat(result).isPresent();
        assertThat(result.get().getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void findByBookingId_whenAbsent_returnsEmpty() {
        Optional<Payment> result = paymentRepository.findByBookingId(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void existsByBookingId_whenExists_returnsTrue() {
        UUID bookingId = UUID.randomUUID();
        paymentRepository.save(aPayment(bookingId, "pi_test_2"));

        assertThat(paymentRepository.existsByBookingId(bookingId)).isTrue();
    }

    @Test
    void existsByBookingId_whenAbsent_returnsFalse() {
        assertThat(paymentRepository.existsByBookingId(UUID.randomUUID())).isFalse();
    }

    @Test
    void findByStripePaymentIntentId_whenExists_returnsPayment() {
        UUID bookingId = UUID.randomUUID();
        paymentRepository.save(aPayment(bookingId, "pi_test_3"));

        Optional<Payment> result = paymentRepository.findByStripePaymentIntentId("pi_test_3");

        assertThat(result).isPresent();
        assertThat(result.get().getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void findByStripePaymentIntentId_whenAbsent_returnsEmpty() {
        Optional<Payment> result = paymentRepository.findByStripePaymentIntentId("pi_does_not_exist");

        assertThat(result).isEmpty();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Payment aPayment(UUID bookingId, String stripePaymentIntentId) {
        Payment p = new Payment();
        p.setBookingId(bookingId);
        p.setUserId(UUID.randomUUID());
        p.setAmountGbp(89.5);
        p.setStatus(PaymentStatus.PENDING);
        p.setStripePaymentIntentId(stripePaymentIntentId);
        return p;
    }
}
