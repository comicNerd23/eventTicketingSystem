package com.ticketing.booking.redis;

import com.ticketing.booking.service.BookingService;
import com.ticketing.booking.service.SeatHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SeatHoldExpiredListener extends KeyExpirationEventMessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiredListener.class);

    private final BookingService bookingService;

    public SeatHoldExpiredListener(RedisMessageListenerContainer listenerContainer, BookingService bookingService) {
        super(listenerContainer);
        this.bookingService = bookingService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        if (!expiredKey.startsWith(SeatHoldService.HOLD_KEY_PREFIX)) {
            return;
        }
        try {
            UUID seatId = UUID.fromString(expiredKey.substring(SeatHoldService.HOLD_KEY_PREFIX.length()));
            bookingService.expireHold(seatId);
        } catch (Exception e) {
            log.error("Failed to process seat-hold expiry for key {}: {}", expiredKey, e.getMessage(), e);
        }
    }
}
