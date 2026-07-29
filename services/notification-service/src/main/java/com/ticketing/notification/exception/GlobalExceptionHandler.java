package com.ticketing.notification.exception;

import com.ticketing.notification.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFound(NotificationNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(NotificationNotFoundForBookingException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFoundForBooking(NotificationNotFoundForBookingException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(NotificationNotFoundForWaitlistEntryException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFoundForWaitlistEntry(NotificationNotFoundForWaitlistEntryException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }
}
