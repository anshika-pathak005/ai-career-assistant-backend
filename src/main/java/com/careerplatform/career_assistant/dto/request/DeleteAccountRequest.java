package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Password confirmation for account deletion.
 *
 * 6.8 / 7.1: this was {@code @RequestParam String password}, which put the user's
 * plaintext password into the web server access log, the browser's address bar and
 * history, every proxy and CDN log in between, the Referer header of subsequent requests,
 * and any APM trace — all retained, none of it rotated.
 *
 * DELETE with a body is legal and Spring handles it.
 */
@Data
public class DeleteAccountRequest {

    @NotBlank(message = "Password is required")
    @Size(max = 72, message = "Password must be 72 characters or fewer")
    private String password;
}
