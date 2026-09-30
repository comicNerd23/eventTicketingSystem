-- Baseline schema for payment-service, generated from the JPA entities by Hibernate's own schema
-- export (ADR-017). Existing dev databases created by ddl-auto=update are baselined at this
-- version instead of re-running it (spring.flyway.baseline-on-migrate in application-dev.yml).

    create table payments (
        amount_gbp float(53) not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        booking_id uuid not null unique,
        id uuid not null,
        user_id uuid not null,
        failure_reason varchar(255),
        status varchar(255) not null check ((status in ('PENDING','SUCCEEDED','FAILED','REFUNDED'))),
        stripe_payment_intent_id varchar(255) unique,
        primary key (id)
    );
