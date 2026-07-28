package com.ticketing.payment.repository;

import com.ticketing.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);
}
