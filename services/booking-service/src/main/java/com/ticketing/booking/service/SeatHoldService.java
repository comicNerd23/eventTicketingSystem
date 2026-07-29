package com.ticketing.booking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class SeatHoldService {

    public static final String HOLD_KEY_PREFIX = "seat-hold:";

    private final StringRedisTemplate redis;
    private final Duration holdTtl;

    public SeatHoldService(StringRedisTemplate redis,
                            @Value("${booking.hold.ttl-seconds:600}") long holdTtlSeconds) {
        this.redis = redis;
        this.holdTtl = Duration.ofSeconds(holdTtlSeconds);
    }

    public boolean acquireHold(UUID seatId) {
        String key = HOLD_KEY_PREFIX + seatId;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "HELD", holdTtl);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseHold(UUID seatId) {
        redis.delete(HOLD_KEY_PREFIX + seatId);
    }
}
