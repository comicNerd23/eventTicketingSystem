package com.ticketing.event.client;

import com.ticketing.event.exception.BookingServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Component
public class BookingServiceClient {

    private final RestClient restClient;

    public BookingServiceClient(RestClient.Builder restClientBuilder,
                                 @Value("${booking.service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public List<ActiveSeat> getActiveSeats(UUID eventId) {
        try {
            ActiveSeat[] seats = restClient.get()
                .uri("/bookings/events/{eventId}/active-seats", eventId)
                .retrieve()
                .body(ActiveSeat[].class);
            return seats == null ? List.of() : List.of(seats);
        } catch (RestClientException e) {
            throw new BookingServiceUnavailableException(eventId, e);
        }
    }
}
