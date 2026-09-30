-- Baseline schema for notification-service, generated from the JPA entities by Hibernate's own schema
-- export (ADR-017). Existing dev databases created by ddl-auto=update are baselined at this
-- version instead of re-running it (spring.flyway.baseline-on-migrate in application-dev.yml).

    create table notifications (
        created_at timestamp(6) with time zone not null,
        booking_id uuid,
        id uuid not null,
        user_id uuid not null,
        waitlist_entry_id uuid,
        body TEXT not null,
        status varchar(255) not null check ((status in ('SENT','FAILED'))),
        subject varchar(255) not null,
        type varchar(255) not null check ((type in ('TICKET_ISSUED','SEAT_HOLD_EXPIRED','BOOKING_CANCELLED','WAITLIST_PROMOTED'))),
        user_email varchar(255) not null,
        primary key (id)
    );
