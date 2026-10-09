package com.careerplatform.career_assistant.exception;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * Translates exceptions into the app's ErrorResponse JSON.
 *
 * <h2>6.5: log levels</h2>
 *
 * Every handler used to call {@code log.error}, including for 404s, 409s and validation
 * failures. Those are client mistakes, not server faults, and logging them at ERROR means
 * alerting fires when somebody typos a URL — which trains everyone to ignore the alert.
 * 4xx is {@code warn} or {@code debug} here; {@code error} is reserved for 5xx, where
 * something on this side is actually broken.
 *
 * <h2>6.5: handlers that were missing</h2>
 *
 * Seven exceptions previously fell through to the catch-all and returned 500. The worst
 * was {@link AccessDeniedException}: a legitimate 403 raised inside a controller was
 * swallowed by {@code @ExceptionHandler(Exception.class)} and reported as a server error,
 * so a permissions problem looked like a bug.
 *
 * <h2>On RFC 7807</h2>
 *
 * Spring 6 supports ProblemDetail natively and it would be the better wire format. It is
 * deliberately not adopted here because it changes the response shape for every existing
 * client. It belongs with a frontend change, not inside a backend refactor.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 4xx: the caller got something wrong ───────────────────────────────────

    /** 404 — entity not found. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return status(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 409 — duplicate resource, e.g. an email that is already registered. */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return status(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 409 — a database constraint rejected the write.
     *
     * 6.5: was a 500. A unique constraint firing is the database telling you the caller
     * sent a duplicate, which is a 409. The message is deliberately generic: the
     * exception text contains the constraint name, the table and often the offending
     * value, none of which should reach a client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return status(HttpStatus.CONFLICT,
                "That change conflicts with existing data. Please check and try again.");
    }

    /** 400 — @Valid failed on a request body; all field errors collected into one message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation failed: {}", message);
        return status(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 400 — validation failed on a request PARAMETER.
     *
     * @Valid on a @RequestBody throws MethodArgumentNotValidException, handled above.
     * Constraints on a @RequestParam under @Validated throw this instead.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("Invalid request parameter");

        log.warn("Constraint violation: {}", message);
        return status(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 400 — the body was not readable: malformed JSON, a wrong type for a field, or an
     * empty body where one is required.
     *
     * 6.5: was a 500. A client sending broken JSON is a client error, and returning 500
     * for it means real server faults are buried in noise. The parse detail is logged but
     * not returned — it quotes the offending input back, which can reflect attacker input
     * into the response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return status(HttpStatus.BAD_REQUEST, "Request body is missing or malformed.");
    }

    /**
     * 400 — a path variable or parameter could not be converted, e.g. /api/v1/resume/abc
     * where a Long is expected.
     *
     * 6.5: was a 500. Anyone editing a URL by hand could produce a server error.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getValue());
        return status(HttpStatus.BAD_REQUEST,
                "'" + ex.getName() + "' is not a valid value for this endpoint.");
    }

    /** 400 — a required query parameter was not sent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return status(HttpStatus.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing.");
    }

    /** 405 — wrong HTTP method for this path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return status(HttpStatus.METHOD_NOT_ALLOWED,
                ex.getMethod() + " method is not supported for this endpoint");
    }

    /**
     * 404 — no handler matched the path.
     *
     * 6.5: this previously produced Spring's HTML error page from a JSON API. Requires
     * spring.web.resources.add-mappings=false, otherwise the static resource handler
     * matches everything and this never fires.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        log.warn("No handler for {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return status(HttpStatus.NOT_FOUND, "No endpoint found for this path.");
    }

    /** 413 — upload over spring.servlet.multipart.max-file-size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected - exceeds the configured multipart limit");
        return status(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large. Maximum size is 5MB.");
    }

    // ── 401 / 403 ─────────────────────────────────────────────────────────────

    /** 401 — wrong email or password. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        // debug, not warn: failed logins are routine traffic and LoginAttemptService
        // already logs the lockout, which is the event worth alerting on.
        log.debug("Authentication failed: {}", ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** 401 — refresh token revoked, reused or expired. */
    @ExceptionHandler(TokenRevokedException.class)
    public ResponseEntity<ErrorResponse> handleTokenRevoked(TokenRevokedException ex) {
        log.debug("Token revoked or expired: {}", ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * 401 — an expired or otherwise invalid JWT reached a controller.
     *
     * 6.5: was a 500. JwtAuthenticationFilter catches these on the normal path, so this
     * covers a token parsed anywhere else. The message is the same for every cause on
     * purpose - distinguishing expired from tampered tells an attacker which part of a
     * forged token to change next.
     */
    @ExceptionHandler({ExpiredJwtException.class, JwtException.class})
    public ResponseEntity<ErrorResponse> handleJwt(JwtException ex) {
        log.debug("JWT rejected: {}", ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED,
                "Invalid or expired token. Please refresh or log in again.");
    }

    /**
     * 401 — a handler asked for the current user but nothing was authenticated.
     *
     * Thrown by CurrentUserIdArgumentResolver. See 6.1: this is the case that used to be
     * an NPE inside a service and therefore a 500.
     */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoCredentials(AuthenticationCredentialsNotFoundException ex) {
        log.warn("Unauthenticated request reached a handler requiring a user: {}", ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    /**
     * 403 — authenticated but not allowed.
     *
     * 6.5: this is the important one. AccessDeniedException raised INSIDE a controller
     * (by @PreAuthorize, for instance) does not reach Spring Security's
     * RestAccessDeniedHandler - it propagates to this advice, where the catch-all
     * converted a legitimate 403 into a 500. Without this handler, every method-level
     * authorization failure looks like a server bug.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return status(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    // ── app-specific ──────────────────────────────────────────────────────────

    /** 400 — file upload validation failed. */
    @ExceptionHandler(FileUploadException.class)
    public ResponseEntity<ErrorResponse> handleFileUpload(FileUploadException ex) {
        log.warn("File upload rejected: {}", ex.getMessage());
        return status(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 429 — monthly generation quota reached. */
    @ExceptionHandler(RoadmapLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRoadmapLimit(RoadmapLimitExceededException ex) {
        log.info("Generation limit reached: {}", ex.getMessage());
        return status(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    /** 502 — the LLM provider failed or returned something unusable. */
    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ErrorResponse> handleAiProvider(AiProviderException ex) {
        // error is right here: a 5xx means something outside the caller's control broke.
        log.error("AI provider error: {}", ex.getMessage());
        return status(HttpStatus.BAD_GATEWAY, "AI analysis failed. Please try again.");
    }

    // ── 500 ───────────────────────────────────────────────────────────────────

    /**
     * Anything unhandled. The real cause and stack trace go to the log with the request
     * id; the client gets a generic message and that same id to quote.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return status(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again later.");
    }

    /** One place that builds the body, so every response carries the request id. */
    private ResponseEntity<ErrorResponse> status(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status.value(), message));
    }
}
