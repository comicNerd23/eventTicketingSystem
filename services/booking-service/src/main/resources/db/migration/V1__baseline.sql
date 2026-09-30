-- Baseline schema for booking-service, generated from the JPA entities by Hibernate's own schema
-- export (ADR-017). Existing dev databases created by ddl-auto=update are baselined at this
-- version instead of re-running it (spring.flyway.baseline-on-migrate in application-dev.yml).

    create table bookings (
        total_amount_gbp float(53),
        cancelled_at timestamp(6) with time zone,
        confirmed_at timestamp(6) with time zone,
        created_at timestamp(6) with time zone not null,
        expired_at timestamp(6) with time zone,
        hold_expires_at timestamp(6) with time zone,
        event_id uuid not null,
        id uuid not null,
        seat_id uuid not null,
        user_id uuid not null,
        event_title varchar(255) not null,
        seat_label varchar(255) not null,
        status varchar(255) not null check ((status in ('HELD','PAYMENT_PENDING','CONFIRMED','CANCELLED','EXPIRED'))),
        ticket_reference varchar(255),
        primary key (id)
    );
