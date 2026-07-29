package com.ticketing.booking.client;

import com.ticketing.booking.exception.EventNotFoundException;
import com.ticketing.booking.exception.EventServiceUnavailableException;
import com.ticketing.booking.exception.SeatNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class EventServiceClient {

    private final RestClient restClient;

    public EventServiceClient(RestClient.Builder restClientBuilder,
                               @Value("${event.service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public EventInfo getEvent(UUID eventId) {
        try {
            EventDto dto = restClient.get()
                .uri("/events/{id}", eventId)
                .retrieve()
                .body(EventDto.class);
            return new EventInfo(dto.id(), dto.title());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new EventNotFoundException(eventId);
            }
            throw new EventServiceUnavailableException(eventId, e);
        } catch (RestClientException e) {
            throw new EventServiceUnavailableException(eventId, e);
        }
    }

    public SeatInfo getSeat(UUID eventId, UUID seatId) {
        try {
            SeatDto dto = restClient.get()
                .uri("/events/{eventId}/seats/{seatId}", eventId, seatId)
                .retrieve()
                .body(SeatDto.class);
            return new SeatInfo(dto.id(), dto.label(), dto.priceGbp());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new SeatNotFoundException(eventId, seatId);
            }
            throw new EventServiceUnavailableException(eventId, e);
        } catch (RestClientException e) {
            throw new EventServiceUnavailableException(eventId, e);
        }
    }

    private record EventDto(UUID id, String title) {
    }

    private record SeatDto(UUID id, String label, Double priceGbp) {
    }
}
