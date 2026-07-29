package com.ticketing.booking.client;

import java.util.UUID;

public record SeatInfo(UUID id, String label, Double priceGbp) {
}
