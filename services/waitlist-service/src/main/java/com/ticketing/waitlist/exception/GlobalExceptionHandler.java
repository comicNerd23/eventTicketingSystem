package com.ticketing.waitlist.exception;

import com.ticketing.waitlist.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WaitlistEntryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntryNotFound(WaitlistEntryNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AlreadyOnWaitlistException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyOnWaitlist(AlreadyOnWaitlistException e, HttpServletRequest req) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse(409, "Conflict", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(EventNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(EventServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleEventServiceUnavailable(EventServiceUnavailableException e, HttpServletRequest req) {
        return ResponseEntity.status(503)
            .body(new ErrorResponse(503, "Service Unavailable", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(400, "Bad Request", message, req.getRequestURI()));
    }
}
