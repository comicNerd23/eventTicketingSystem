package com.ticketing.payment.exception;

import com.ticketing.payment.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(PaymentNotFoundForBookingException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFoundForBooking(PaymentNotFoundForBookingException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MissingStripeSignatureException.class)
    public ResponseEntity<ErrorResponse> handleMissingSignature(MissingStripeSignatureException e, HttpServletRequest req) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(400, "Bad Request", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(UnsupportedWebhookEventException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedWebhookEvent(UnsupportedWebhookEventException e, HttpServletRequest req) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(400, "Bad Request", e.getMessage(), req.getRequestURI()));
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
