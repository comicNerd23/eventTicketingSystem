-- Baseline schema for waitlist-service, generated from the JPA entities by Hibernate's own schema
-- export (ADR-017). Existing dev databases created by ddl-auto=update are baselined at this
-- version instead of re-running it (spring.flyway.baseline-on-migrate in application-dev.yml).

    create table waitlist_entries (
        joined_at timestamp(6) with time zone not null,
        offer_expires_at timestamp(6) with time zone,
        promoted_at timestamp(6) with time zone,
        event_id uuid not null,
        id uuid not null,
        user_id uuid not null,
        event_title varchar(255) not null,
        status varchar(255) not null check ((status in ('WAITING','PROMOTED','EXPIRED','LEFT'))),
        primary key (id)
    );
