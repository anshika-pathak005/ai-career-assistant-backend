package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.ChangePasswordRequest;
import com.careerplatform.career_assistant.dto.request.DeleteAccountRequest;
import com.careerplatform.career_assistant.dto.response.AccountDeletionResponse;
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.UserSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/user/settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    /**
     * PUT /api/v1/user/settings/password
     * Verifies the current password, then revokes every refresh token and bumps the
     * token version so existing access tokens stop working too.
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @CurrentUserId Long userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        log.info("Password change request for userId: {}", userId);
        userSettingsService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/v1/user/settings/account
     * Schedules the account for deletion after the configured grace period.
     *
     * 6.8: the password moved from a query parameter into the body. As a @RequestParam it
     * was written in plaintext to access logs, browser history, proxy logs and Referer
     * headers. See DeleteAccountRequest.
     *
     * 7.1: <b>202, not 204.</b> The status is part of the contract here - nothing has
     * been deleted when this returns, and 204 would say otherwise. The body carries the
     * date so the client can show what is about to happen and offer the cancel below.
     */
    @DeleteMapping("/account")
    public ResponseEntity<AccountDeletionResponse> requestAccountDeletion(
            @CurrentUserId Long userId,
            @Valid @RequestBody DeleteAccountRequest request) {
        log.info("Account deletion requested for userId: {}", userId);
        var scheduledFor = userSettingsService.requestAccountDeletion(userId, request.getPassword());
        return ResponseEntity.accepted().body(AccountDeletionResponse.scheduled(scheduledFor));
    }

    /**
     * POST /api/v1/user/settings/account/deletion/cancel
     * Stops a pending deletion. Requires the password again - see the service.
     *
     * A POST rather than a DELETE on the pending request: DELETE on a sub-resource of
     * "account" is one typo away from the endpoint above, and the two have opposite
     * effects.
     */
    @PostMapping("/account/deletion/cancel")
    public ResponseEntity<Void> cancelAccountDeletion(
            @CurrentUserId Long userId,
            @Valid @RequestBody DeleteAccountRequest request) {
        log.info("Account deletion cancellation requested for userId: {}", userId);
        userSettingsService.cancelAccountDeletion(userId, request.getPassword());
        return ResponseEntity.noContent().build();
    }
}
