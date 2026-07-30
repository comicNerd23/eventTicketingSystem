package com.ticketing.booking.dto;

import java.util.UUID;

public record EventSeatCountResponse(UUID eventId, int activeSeatCount) {
}
