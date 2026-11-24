package dev.nexus.core.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    /**
     * 领域层用 IllegalArgumentException 送出的原因码形如 theme_locked / outfit_not_owned /
     * invalid_appearance_request_id，全部带下划线。只回传这种 token，调用方才能区分"主题没解锁"
     * 和"请求体写错"；其余异常消息（NumberFormatException、反序列化细节、裸英文单词等）仍折叠成
     * invalid_request，避免把内部实现漏给调用方。
     */
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+){1,6}");

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalidArgument(IllegalArgumentException e) {
        String message = e.getMessage();
        return ResponseEntity.badRequest().body(
            Map.of("error", message != null && REASON.matcher(message).matches() ? message : "invalid_request"));
    }

    @ExceptionHandler({dev.nexus.core.security.TokenCredentials.Unavailable.class,dev.nexus.core.security.BrowserSessionStore.Unavailable.class})
    ResponseEntity<?> credentialsUnavailable(RuntimeException error){return ResponseEntity.status(503).body(Map.of("error",error.getMessage()));}

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "request_failed" : e.getReason()));
    }
}
