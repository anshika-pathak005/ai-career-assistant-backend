package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.ChangePasswordRequest;
import com.careerplatform.career_assistant.service.UserSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/user/settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    /**
     * PUT /api/user/settings/password
     * Changes user password.
     * Validates current password before updating.
     * Revokes all refresh tokens after change — forces re-login on all devices.
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        log.info("Password change request received");
        userSettingsService.changePassword(request);
        return ResponseEntity.noContent().build(); // 204
    }

    /**
     * DELETE /api/user/settings/account
     * Deletes user account and all associated data.
     * Requires password confirmation before deletion.
     * Permanent — cannot be undone.
     */
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(
            @RequestParam String password) {
        log.info("Account deletion request received");
        userSettingsService.deleteAccount(password);
        return ResponseEntity.noContent().build(); // 204
    }
}