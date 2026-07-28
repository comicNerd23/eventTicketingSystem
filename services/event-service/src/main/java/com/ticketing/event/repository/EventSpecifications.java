package com.ticketing.event.repository;

import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventCategory;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class EventSpecifications {

    private EventSpecifications() {}

    public static Specification<Event> withFilters(String city, EventCategory category, LocalDate dateFrom, LocalDate dateTo) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (city != null && !city.isBlank()) {
                predicate = cb.and(predicate, cb.equal(cb.lower(root.get("city")), city.toLowerCase()));
            }
            if (category != null) {
                predicate = cb.and(predicate, cb.equal(root.get("category"), category));
            }
            if (dateFrom != null) {
                Instant from = dateFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("startsAt"), from));
            }
            if (dateTo != null) {
                Instant to = dateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                predicate = cb.and(predicate, cb.lessThan(root.get("startsAt"), to));
            }
            return predicate;
        };
    }
}
