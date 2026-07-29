package com.ticketing.notification.controller;

import com.ticketing.notification.dto.NotificationResponse;
import com.ticketing.notification.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationResponse> getNotification(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(notificationService.getNotification(notificationId));
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<NotificationResponse> getNotificationByBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(notificationService.getNotificationByBooking(bookingId));
    }
}
