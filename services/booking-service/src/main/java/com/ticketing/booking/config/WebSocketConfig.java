package com.ticketing.booking.config;

import com.ticketing.booking.websocket.SeatStatusWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SeatStatusWebSocketHandler seatStatusWebSocketHandler;

    public WebSocketConfig(SeatStatusWebSocketHandler seatStatusWebSocketHandler) {
        this.seatStatusWebSocketHandler = seatStatusWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(seatStatusWebSocketHandler, "/bookings/ws/events/*/seats")
            .setAllowedOrigins("*");
    }
}
