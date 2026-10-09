package com.careerplatform.career_assistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a request ID to each incoming HTTP request and makes it available
 * for logging and request tracing.
 *
 * The request ID helps identify log entries associated with a particular
 * request, making it easier to investigate errors and trace request
 * processing across different application components.
 *
 * The ID is stored in the SLF4J Mapped Diagnostic Context (MDC), allowing
 * logging configurations to include it automatically in log messages.
 * It is also returned to the client through the X-Request-Id response
 * header, allowing the client to provide the ID when reporting an issue.
 *
 * The filter executes at the highest Spring filter precedence so that the
 * request ID is available early in request processing, including when
 * authentication or authorization rejects a request.
 *
 * MDC stores contextual values associated with the current thread. Since
 * servlet containers reuse threads for multiple requests, the request ID
 * must be removed after request processing to prevent it from appearing
 * in logs for an unrelated request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = resolveRequestId(request);

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Uses an inbound X-Request-Id when there is one, so a trace started at the load
     * balancer or in the frontend carries through.
     *
     * The inbound value is client-controlled, so it is length-capped and stripped of
     * anything that is not alphanumeric, a dash or an underscore. Without that, a caller
     * could inject newlines into every log line this request produces and forge entries —
     * log injection is the reason this is not simply passed through.
     */
    private String resolveRequestId(HttpServletRequest request) {
        String inbound = request.getHeader(HEADER);

        if (inbound != null && !inbound.isBlank()) {
            String cleaned = inbound.replaceAll("[^A-Za-z0-9_-]", "");
            if (!cleaned.isEmpty()) {
                return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
            }
        }

        return UUID.randomUUID().toString();
    }

    /** The current request's id, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
