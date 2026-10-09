package com.careerplatform.career_assistant.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the authenticated caller's userId to a controller method parameter.
 *
 * <pre>
 * public ResponseEntity&lt;UserProfileResponse&gt; getProfile(&#64;CurrentUserId Long userId)
 * </pre>
 *
 * 6.1: replaces this, which was copy-pasted verbatim into five services:
 *
 * <pre>
 * return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
 * </pre>
 *
 * That version NPE'd when unauthenticated and threw NumberFormatException if the
 * principal was not numeric — both surfaced as a 500 through the catch-all handler.
 * {@link CurrentUserIdArgumentResolver} turns the first case into a 401 and makes the
 * second impossible, because it reads the typed userId off {@link AuthenticatedUser}
 * instead of parsing a string back out of the principal's name.
 *
 * The services now take a userId parameter, which also makes them unit-testable without
 * standing up a SecurityContext.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
