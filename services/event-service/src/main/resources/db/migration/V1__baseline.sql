-- Baseline schema for event-service, generated from the JPA entities by Hibernate's own schema
-- export (ADR-017). Existing dev databases created by ddl-auto=update are baselined at this
-- version instead of re-running it (spring.flyway.baseline-on-migrate in application-dev.yml).

    create table events (
        available_seats integer not null,
        total_seats integer not null,
        created_at timestamp(6) with time zone not null,
        ends_at timestamp(6) with time zone not null,
        starts_at timestamp(6) with time zone not null,
        id uuid not null,
        organizer_id uuid not null,
        venue_id uuid not null,
        description varchar(2000),
        category varchar(255) not null check ((category in ('CONCERT','SPORTS','THEATRE','COMEDY','OTHER'))),
        city varchar(255) not null,
        image_url varchar(255),
        status varchar(255) not null check ((status in ('DRAFT','PUBLISHED','SOLD_OUT','CANCELLED'))),
        title varchar(255) not null,
        venue_name varchar(255) not null,
        primary key (id)
    );

    create table seats (
        price_gbp float(53) not null,
        row_number integer not null,
        seat_number integer not null,
        event_id uuid not null,
        id uuid not null,
        section_id uuid not null,
        label varchar(255) not null,
        section_name varchar(255) not null,
        primary key (id)
    );

    create table sections (
        price_gbp float(53) not null,
        rows integer not null,
        seats_per_row integer not null,
        id uuid not null,
        venue_id uuid not null,
        name varchar(255) not null,
        primary key (id)
    );

    create table venues (
        capacity integer not null,
        id uuid not null,
        address varchar(255) not null,
        city varchar(255) not null,
        country varchar(255) not null,
        name varchar(255) not null,
        primary key (id)
    );

    alter table if exists sections
       add constraint FK7hxfe3d22kdbyo1eynum0ehk8
       foreign key (venue_id)
       references venues;
