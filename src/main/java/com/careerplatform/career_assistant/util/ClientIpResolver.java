package com.careerplatform.career_assistant.util;

import com.careerplatform.career_assistant.config.properties.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves which client a request came from, for rate limiting and login lockout.
 * Returns the TCP peer address, which the client cannot forge.
 * X-Forwarded-For is client-supplied, so it is only read when
 * app.rate-limit.trust-forwarded-headers is enabled — set that only when a proxy you
 * control overwrites the header before the request reaches this app.
 *
 * Both callers use this one component so they can never disagree about who a caller is.
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final AppProperties appProperties;

    public String resolve(HttpServletRequest request) {
        if (appProperties.rateLimit().trustForwardedHeaders()) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                // leftmost entry is the original client as recorded by the proxy
                return xForwardedFor.split(",")[0].trim();
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
