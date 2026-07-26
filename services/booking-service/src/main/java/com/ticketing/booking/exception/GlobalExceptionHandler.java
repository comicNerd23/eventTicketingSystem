package com.ticketing.booking.exception;

import com.ticketing.booking.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatAlreadyHeldException.class)
    public ResponseEntity<ErrorResponse> handleSeatAlreadyHeld(SeatAlreadyHeldException e, HttpServletRequest req) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse(409, "Conflict", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(BookingNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BookingNotHeldException.class)
    public ResponseEntity<ErrorResponse> handleNotHeld(BookingNotHeldException e, HttpServletRequest req) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse(409, "Conflict", e.getMessage(), req.getRequestURI()));
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
