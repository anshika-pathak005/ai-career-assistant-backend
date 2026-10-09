package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    @Size(max = 72, message = "Password must be 72 characters or fewer")
    private String currentPassword;

    // 6.6: max = 72 because BCrypt truncates there. See RegisterRequest.
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    private String newPassword;

    @NotBlank(message = "Confirm password is required")
    @Size(max = 72, message = "Password must be 72 characters or fewer")
    private String confirmPassword;
}
