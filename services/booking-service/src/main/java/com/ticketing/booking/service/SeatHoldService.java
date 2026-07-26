package com.ticketing.booking.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class SeatHoldService {

    private static final String HOLD_KEY_PREFIX = "seat-hold:";
    private static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public SeatHoldService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean acquireHold(UUID seatId) {
        String key = HOLD_KEY_PREFIX + seatId;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "HELD", HOLD_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseHold(UUID seatId) {
        redis.delete(HOLD_KEY_PREFIX + seatId);
    }
}
