package com.ticketing.booking.websocket;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SeatStatusWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatStatusWebSocketHandler.class);
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("/bookings/ws/events/([^/]+)/seats");
    private static final String EVENT_ID_ATTRIBUTE = "eventId";

    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<WebSocketSession>> sessionsByEventId = new ConcurrentHashMap<>();

    public SeatStatusWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID eventId = parseEventId(session);
        if (eventId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        sessionsByEventId.computeIfAbsent(eventId, id -> ConcurrentHashMap.newKeySet()).add(session);
        session.getAttributes().put(EVENT_ID_ATTRIBUTE, eventId);
        log.debug("Seat-status WebSocket connected: event={} session={}", eventId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID eventId = (UUID) session.getAttributes().get(EVENT_ID_ATTRIBUTE);
        if (eventId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByEventId.get(eventId);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    public void broadcast(UUID eventId, UUID seatId, String status) {
        Set<WebSocketSession> sessions = sessionsByEventId.get(eventId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String json;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seatId", seatId.toString());
            payload.put("status", status);
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize seat status update for event {}: {}", eventId, e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                log.warn("Failed to send seat status update to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private UUID parseEventId(WebSocketSession session) {
        Matcher matcher = EVENT_ID_PATTERN.matcher(session.getUri().getPath());
        if (!matcher.find()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
