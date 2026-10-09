package com.careerplatform.career_assistant.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the authenticated userId to the logging MDC, next to the request id (7.2).
 *
 * With structured logging switched on, every line a request produces carries both, so
 * "show me everything this user did today" and "show me everything in this request" are
 * both one query rather than a reconstruction from timestamps.
 *
 * <h2>Ordering</h2>
 *
 * Spring Boot registers the security chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER}
 * (-100). This filter is auto-registered at order 0, so it sits <b>after</b> it and the
 * SecurityContext is already populated by the time it runs. Putting it at
 * HIGHEST_PRECEDENCE, where RequestIdFilter lives, would read an empty context and put
 * nothing in the MDC - and would do so silently.
 *
 * The consequence of that ordering is worth knowing: a request rejected by the security
 * chain never reaches this filter, so 401s log without a userId. That is correct rather
 * than unfortunate - there is no authenticated user to name.
 *
 * <h2>The finally block</h2>
 *
 * Same reason as RequestIdFilter: Tomcat pools threads, and an MDC entry left behind is
 * inherited by the next unrelated request, which then logs under someone else's userId.
 * In a log that is used to investigate account activity, that is worse than having no
 * userId at all.
 */
@Component
@Order(0)
public class UserMdcFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userId = currentUserId();
        if (userId != null) {
            MDC.put(MDC_KEY, userId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The authenticated userId, or null when there is no authenticated user.
     * Matches RateLimitInterceptor's treatment of "anonymousUser", which Spring uses as
     * the principal name for unauthenticated requests.
     */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }
}
