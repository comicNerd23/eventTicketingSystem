package com.ticketing.notification.controller;

import com.ticketing.notification.domain.Notification;
import com.ticketing.notification.domain.NotificationStatus;
import com.ticketing.notification.domain.NotificationType;
import com.ticketing.notification.dto.NotificationResponse;
import com.ticketing.notification.exception.NotificationNotFoundException;
import com.ticketing.notification.exception.NotificationNotFoundForBookingException;
import com.ticketing.notification.exception.NotificationNotFoundForWaitlistEntryException;
import com.ticketing.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationService notificationService;

    // ── GET /notifications/{id} ──────────────────────────────────────────────

    @Test
    void getNotification_whenFound_returns200() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.getNotification(notificationId))
            .willReturn(NotificationResponse.from(aNotification(notificationId)));

        mvc.perform(get("/notifications/{id}", notificationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(notificationId.toString()))
            .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void getNotification_whenNotFound_returns404() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.getNotification(notificationId))
            .willThrow(new NotificationNotFoundException(notificationId));

        mvc.perform(get("/notifications/{id}", notificationId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /notifications/bookings/{bookingId} ──────────────────────────────

    @Test
    void getNotificationByBooking_whenFound_returns200() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(notificationService.getNotificationByBooking(bookingId))
            .willReturn(NotificationResponse.from(aNotification(UUID.randomUUID())));

        mvc.perform(get("/notifications/bookings/{bookingId}", bookingId))
            .andExpect(status().isOk());
    }

    @Test
    void getNotificationByBooking_whenNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        given(notificationService.getNotificationByBooking(bookingId))
            .willThrow(new NotificationNotFoundForBookingException(bookingId));

        mvc.perform(get("/notifications/bookings/{bookingId}", bookingId))
            .andExpect(status().isNotFound());
    }

    // ── GET /notifications/waitlist-entries/{waitlistEntryId} ────────────────

    @Test
    void getNotificationByWaitlistEntry_whenFound_returns200() throws Exception {
        UUID waitlistEntryId = UUID.randomUUID();
        Notification notification = aNotification(UUID.randomUUID());
        notification.setWaitlistEntryId(waitlistEntryId);
        given(notificationService.getNotificationByWaitlistEntry(waitlistEntryId))
            .willReturn(NotificationResponse.from(notification));

        mvc.perform(get("/notifications/waitlist-entries/{waitlistEntryId}", waitlistEntryId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.waitlistEntryId").value(waitlistEntryId.toString()));
    }

    @Test
    void getNotificationByWaitlistEntry_whenNotFound_returns404() throws Exception {
        UUID waitlistEntryId = UUID.randomUUID();
        given(notificationService.getNotificationByWaitlistEntry(waitlistEntryId))
            .willThrow(new NotificationNotFoundForWaitlistEntryException(waitlistEntryId));

        mvc.perform(get("/notifications/waitlist-entries/{waitlistEntryId}", waitlistEntryId))
            .andExpect(status().isNotFound());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private Notification aNotification(UUID id) {
        Notification n = new Notification();
        n.setId(id);
        n.setBookingId(UUID.randomUUID());
        n.setUserId(UUID.randomUUID());
        n.setUserEmail("user@example.com");
        n.setType(NotificationType.TICKET_ISSUED);
        n.setStatus(NotificationStatus.SENT);
        n.setSubject("Your ticket for Test Event");
        n.setBody("body text");
        return n;
    }
}
