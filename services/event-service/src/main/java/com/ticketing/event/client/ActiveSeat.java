package com.ticketing.event.client;

import java.util.UUID;

public record ActiveSeat(UUID seatId, String status) {
}
