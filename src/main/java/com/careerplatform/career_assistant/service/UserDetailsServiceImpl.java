package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// Spring Security requires this to load user from DB during authentication
// UserDetailsService is a Spring Security interface — we implement it here
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // Spring Security calls this with the userId (we use userId as "username")
    // Returns UserDetails — Spring Security's representation of a user
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        log.info("Loading user by userId: {}", userId);

        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> {
                    log.error("User not found with userId: {}", userId);
                    return new UsernameNotFoundException("User not found with userId: " + userId);
                });

        // Spring Security's User builder — wraps our User entity into UserDetails
        // authorities left empty for now — will add roles later (ROLE_USER, ROLE_ADMIN)
        return org.springframework.security.core.userdetails.User
                .withUsername(String.valueOf(user.getId()))
                .password(user.getPasswordHash())
                .authorities(java.util.Collections.emptyList())
                .build();
    }


}