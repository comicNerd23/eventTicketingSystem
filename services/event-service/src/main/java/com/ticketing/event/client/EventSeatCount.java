package com.ticketing.event.client;

import java.util.UUID;

public record EventSeatCount(UUID eventId, int activeSeatCount) {
}
