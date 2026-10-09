package com.careerplatform.career_assistant.exception;

import com.careerplatform.career_assistant.config.RequestIdFilter;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Shape of the error JSON sent to the frontend.
 *
 * 6.5: gained a requestId. Without one, a user reporting an error gave you nothing to
 * search the logs with. The same value is on the X-Request-Id response header and on
 * every log line for that request.
 *
 * <p>Wire format is additive - existing clients that read only status/message/timestamp
 * are unaffected.
 */
@Data
@AllArgsConstructor
public class ErrorResponse {

    private int status;
    private String message;
    private LocalDateTime timestamp;

    /**
     * Correlation id for this request. Null only if the response is produced outside a
     * request, which should not happen in practice.
     */
    private String requestId;

    /**
     * Builds a response stamped with the current time and request id.
     *
     * Every call site goes through this rather than the all-args constructor, so nobody
     * has to remember to pass the id and no error can be emitted without one.
     */
    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, LocalDateTime.now(), RequestIdFilter.current());
    }
}
