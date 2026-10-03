package com.myexampleproject.common.exception;

import com.myexampleproject.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.Instant;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @org.springframework.beans.factory.annotation.Value("${app.errors.log-stacktraces:true}")
    private boolean logStacktraces = true;

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> business(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getStatusCode().is5xxServerError() ? "Service temporarily unavailable" : ex.getReason();
        return response(ex.getStatusCode().value(), "BUSINESS_ERROR", message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage()).distinct().sorted().reduce((a,b) -> a + "; " + b).orElse("Invalid request");
        return response(400, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception ex, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Invalid request data", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception ex, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "HTTP method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception ex, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", request);
    }

    @ExceptionHandler({NoSuchElementException.class, jakarta.persistence.EntityNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> missing(Exception ex, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> conflict(Exception ex, HttpServletRequest request) {
        return response(409, "CONFLICT", "Request conflicts with current data; refresh and retry", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(Exception ex, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "Access denied", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> unauthenticated(Exception ex, HttpServletRequest request) {
        return response(401, "UNAUTHORIZED", "Authentication required", request);
    }

    @ExceptionHandler(java.util.concurrent.CompletionException.class)
    public ResponseEntity<ErrorResponse> completion(java.util.concurrent.CompletionException ex, HttpServletRequest request) {
        Throwable cause = ex.getCause();
        if (cause instanceof ResponseStatusException status) return business(status, request);
        return unexpected(ex, request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(Exception ex, HttpServletRequest request) {
        return response(413, "UPLOAD_TOO_LARGE", "Upload exceeds supported size", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        if (logStacktraces) log.error("Request failed: {}", request.getRequestURI(), ex);
        else log.error("Request failed path={} exception={}", request.getRequestURI(), ex.getClass().getSimpleName());
        return response(500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred", request);
    }

    private ResponseEntity<ErrorResponse> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.builder().timestamp(Instant.now().toString())
                .status(status).code(code).message(message == null ? "Request failed" : message).path(request.getRequestURI()).build());
    }
}
