package com.careerplatform.career_assistant.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal placed in the SecurityContext by JwtAuthenticationFilter.
 * Carries the userId, the user's current tokenVersion and their role.
 * Built by UserDetailsServiceImpl from the User row.
 *
 * getUsername() returns the userId as a String. Services read the current user via
 * SecurityContextHolder.getAuthentication().getName(), which delegates to it — changing
 * what it returns would silently break every one of them.
 */
@Getter
public class AuthenticatedUser implements UserDetails {

    private final Long userId;
    private final String passwordHash;

    /**
     * The version stamped into every token issued for this user.
     * A token whose "tv" claim does not match has been revoked — see
     * JwtAuthenticationFilter.
     */
    private final int tokenVersion;

    private final String role;

    public AuthenticatedUser(Long userId, String passwordHash, int tokenVersion, String role) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.tokenVersion = tokenVersion;
        this.role = (role == null || role.isBlank()) ? "ROLE_USER" : role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** userId as a String — see the class Javadoc before changing this. */
    @Override
    public String getUsername() {
        return String.valueOf(userId);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
