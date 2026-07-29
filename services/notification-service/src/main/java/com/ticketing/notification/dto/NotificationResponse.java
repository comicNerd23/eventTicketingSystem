package com.ticketing.notification.dto;

import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public class NotificationResponse {

    private UUID id;
    private UUID bookingId;
    private UUID userId;
    private String userEmail;
    private NotificationType type;
    private NotificationStatus status;
    private String subject;
    private String body;
    private Instant createdAt;

    public static NotificationResponse from(Notification n) {
        NotificationResponse r = new NotificationResponse();
        r.id = n.getId();
        r.bookingId = n.getBookingId();
        r.userId = n.getUserId();
        r.userEmail = n.getUserEmail();
        r.type = n.getType();
        r.status = n.getStatus();
        r.subject = n.getSubject();
        r.body = n.getBody();
        r.createdAt = n.getCreatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getUserId() { return userId; }
    public String getUserEmail() { return userEmail; }
    public NotificationType getType() { return type; }
    public NotificationStatus getStatus() { return status; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
