package com.ticketing.booking.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class SeatHoldServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    SeatHoldService seatHoldService;

    @BeforeEach
    void setUp() {
        seatHoldService = new SeatHoldService(redis, 600);
    }

    @Test
    void acquireHold_whenKeyAbsent_setsKeyWithTtlAndReturnsTrue() {
        UUID seatId = UUID.randomUUID();
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.setIfAbsent(eq("seat-hold:" + seatId), eq("HELD"), any(Duration.class)))
            .willReturn(true);

        assertThat(seatHoldService.acquireHold(seatId)).isTrue();

        then(valueOps).should().setIfAbsent(
            eq("seat-hold:" + seatId),
            eq("HELD"),
            eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void acquireHold_whenKeyAlreadyExists_returnsFalse() {
        UUID seatId = UUID.randomUUID();
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.setIfAbsent(any(), any(), any(Duration.class))).willReturn(false);

        assertThat(seatHoldService.acquireHold(seatId)).isFalse();
    }

    @Test
    void acquireHold_whenRedisReturnsNull_treatAsFailureAndReturnsFalse() {
        // Redis can return null if the connection is in pipeline/multi mode
        UUID seatId = UUID.randomUUID();
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.setIfAbsent(any(), any(), any(Duration.class))).willReturn(null);

        assertThat(seatHoldService.acquireHold(seatId)).isFalse();
    }

    @Test
    void releaseHold_deletesExactKey() {
        UUID seatId = UUID.randomUUID();

        seatHoldService.releaseHold(seatId);

        then(redis).should().delete("seat-hold:" + seatId);
    }
}
