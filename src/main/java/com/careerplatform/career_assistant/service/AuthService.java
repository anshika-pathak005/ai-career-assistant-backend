package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RegisterRequest;
import com.careerplatform.career_assistant.dto.request.RegisterStep2Request;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.DuplicateResourceException;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Step 1 registration — saves basic user info, hashes password, sets wizardStep = 1
    public User registerStep1(RegisterRequest request) {

        log.info("Registering new user with email: {}", request.getEmail());

        // throw 409 if email already taken — caught by GlobalExceptionHandler
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed — email already registered: {}", request.getEmail());
            throw new DuplicateResourceException("Email already registered");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .wizardStep(1)
                .isProfileComplete(false)
                .build();

        User savedUser = userRepository.save(user);
        log.info("User registered successfully with id: {}", savedUser.getId());

        return savedUser;
    }

    // Step 2 registration — updates remaining profile fields, marks profile complete
    public User updateStep2(Long userId, RegisterStep2Request request) {

        log.info("Step 2 update started for userId: {}", userId);

        // find user — throws 404 if not found, caught by GlobalExceptionHandler
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        user.setUserCategory(request.getUserCategory());
        user.setDreamJob(request.getDreamJob());
        user.setIndustries(request.getIndustries());
        user.setSkills(request.getSkills());
        user.setExperienceYears(request.getExperienceYears());
        user.setCurrentRole(request.getCurrentRole());
        user.setWizardStep(2);
        user.setIsProfileComplete(true);

        User updatedUser = userRepository.save(user);
        log.info("Step 2 completed for userId: {}, profile marked complete", userId);

        return updatedUser;
    }
}