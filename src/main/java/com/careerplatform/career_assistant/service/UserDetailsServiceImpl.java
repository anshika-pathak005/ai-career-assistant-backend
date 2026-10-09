package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Spring Security requires this to load user from DB during authentication
// The "username" here is the userId, since that is what the JWT subject holds
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads a user as an AuthenticatedUser, carrying their tokenVersion and role.
     * Called by JwtAuthenticationFilter on every authenticated request, which is why
     * the revocation check costs nothing extra — this row is loaded either way.
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser loadAuthenticatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        return new AuthenticatedUser(
                user.getId(),
                user.getPasswordHash(),
                user.getTokenVersion() != null ? user.getTokenVersion() : 0,
                user.getRole()
        );
    }

    /**
     * Spring Security's contract method, required by the DaoAuthenticationProvider bean.
     * Delegates to loadAuthenticatedUser after parsing the userId.
     */
    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUser loadUserByUsername(String userId) throws UsernameNotFoundException {
        try {
            return loadAuthenticatedUser(Long.parseLong(userId));
        } catch (NumberFormatException ex) {
            // The principal is always a numeric userId, so anything else is a caller
            // bug — surface it as a 401, not a 500.
            throw new UsernameNotFoundException("Invalid user identifier: " + userId);
        }
    }
}
