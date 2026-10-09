package com.careerplatform.career_assistant.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gets the authenticated user's userId for parameters marked with @CurrentUserId.
 *
 * Registered in WebMvcConfig.
 * Gets the userId directly from AuthenticatedUser instead of reading and
 * converting authentication.getName().
 *
 * If no authenticated application user is found, this resolver throws an
 * exception instead of returning null. This prevents the problem from reaching
 * the service layer and causing a NullPointerException or an invalid query.
 *
 * GlobalExceptionHandler converts the exception into a 401 Unauthorized response.
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "No authenticated user for this request");
        }

        /*
        * Make sure the principal is our AuthenticatedUser.
        * Anonymous requests may have a different principal, so they cannot provide
        * an application userId.
        */
        // Only AuthenticatedUser can provide the application userId.
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Request is not authenticated as an application user");
        }

        return user.getUserId();
    }
}
