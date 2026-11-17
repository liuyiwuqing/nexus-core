package dev.nexus.core.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    @ExceptionHandler({dev.nexus.core.security.TokenCredentials.Unavailable.class,dev.nexus.core.security.BrowserSessionStore.Unavailable.class})
    ResponseEntity<?> credentialsUnavailable(RuntimeException error){return ResponseEntity.status(503).body(Map.of("error",error.getMessage()));}

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "request_failed" : e.getReason()));
    }
}
