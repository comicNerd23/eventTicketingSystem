package com.ticketing.booking.websocket;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SeatStatusWebSocketHandlerTest {

    private final SeatStatusWebSocketHandler handler = new SeatStatusWebSocketHandler(new ObjectMapper());

    private UUID eventA;
    private UUID eventB;

    @BeforeEach
    void setUp() {
        eventA = UUID.randomUUID();
        eventB = UUID.randomUUID();
    }

    @Test
    void broadcastOnlyReachesSessionsForTheMatchingEvent() throws Exception {
        WebSocketSession sessionForEventA = fakeSession(eventA);
        WebSocketSession sessionForEventB = fakeSession(eventB);

        handler.afterConnectionEstablished(sessionForEventA);
        handler.afterConnectionEstablished(sessionForEventB);

        UUID seatId = UUID.randomUUID();
        handler.broadcast(eventA, seatId, "HELD");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(sessionForEventA).sendMessage(captor.capture());
        verify(sessionForEventB, never()).sendMessage(any());

        assertThat(captor.getValue().getPayload())
            .contains("\"seatId\":\"" + seatId + "\"")
            .contains("\"status\":\"HELD\"");
    }

    @Test
    void closedOrRemovedSessionsAreNotSentTo() throws Exception {
        WebSocketSession session = fakeSession(eventA);
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        handler.broadcast(eventA, UUID.randomUUID(), "AVAILABLE");

        verify(session, never()).sendMessage(any());
    }

    @Test
    void connectionWithAnInvalidEventIdIsClosed() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getUri()).willReturn(URI.create("ws://localhost:8082/bookings/ws/events/not-a-uuid/seats"));

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.BAD_DATA);
    }

    private WebSocketSession fakeSession(UUID eventId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getUri()).willReturn(URI.create("ws://localhost:8082/bookings/ws/events/" + eventId + "/seats"));
        given(session.getAttributes()).willReturn(new HashMap<>());
        given(session.isOpen()).willReturn(true);
        given(session.getId()).willReturn(UUID.randomUUID().toString());
        return session;
    }
}
