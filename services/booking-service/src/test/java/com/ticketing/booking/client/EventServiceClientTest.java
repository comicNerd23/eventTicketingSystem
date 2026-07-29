package com.ticketing.booking.client;

import com.ticketing.booking.exception.EventNotFoundException;
import com.ticketing.booking.exception.EventServiceUnavailableException;
import com.ticketing.booking.exception.SeatNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EventServiceClientTest {

    private static final String BASE_URL = "http://event-service";

    @Test
    void getEvent_whenFound_returnsEventInfo() {
        UUID eventId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId))
            .andRespond(withSuccess(
                "{\"id\":\"" + eventId + "\",\"title\":\"Coldplay: Music of the Spheres Tour\"}",
                MediaType.APPLICATION_JSON));

        EventInfo info = client.getEvent(eventId);

        assertThat(info.id()).isEqualTo(eventId);
        assertThat(info.title()).isEqualTo("Coldplay: Music of the Spheres Tour");
        server.verify();
    }

    @Test
    void getEvent_whenNotFound_throwsEventNotFoundException() {
        UUID eventId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getEvent(eventId))
            .isInstanceOf(EventNotFoundException.class)
            .hasMessageContaining(eventId.toString());
    }

    @Test
    void getEvent_whenEventServiceErrors_throwsEventServiceUnavailableException() {
        UUID eventId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.getEvent(eventId))
            .isInstanceOf(EventServiceUnavailableException.class);
    }

    // ── getSeat ───────────────────────────────────────────────────────────────

    @Test
    void getSeat_whenFound_returnsSeatInfo() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId + "/seats/" + seatId))
            .andRespond(withSuccess(
                "{\"id\":\"" + seatId + "\",\"label\":\"Floor-R3-S12\",\"priceGbp\":89.5}",
                MediaType.APPLICATION_JSON));

        SeatInfo info = client.getSeat(eventId, seatId);

        assertThat(info.id()).isEqualTo(seatId);
        assertThat(info.label()).isEqualTo("Floor-R3-S12");
        assertThat(info.priceGbp()).isEqualTo(89.5);
        server.verify();
    }

    @Test
    void getSeat_whenNotFound_throwsSeatNotFoundException() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId + "/seats/" + seatId))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getSeat(eventId, seatId))
            .isInstanceOf(SeatNotFoundException.class)
            .hasMessageContaining(seatId.toString());
    }

    @Test
    void getSeat_whenEventServiceErrors_throwsEventServiceUnavailableException() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventServiceClient client = new EventServiceClient(builder, BASE_URL);

        server.expect(requestTo(BASE_URL + "/events/" + eventId + "/seats/" + seatId))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.getSeat(eventId, seatId))
            .isInstanceOf(EventServiceUnavailableException.class);
    }
}
