package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    // 254 is the maximum length of an email address per RFC 5321.
    @Size(max = 254, message = "Email must be 254 characters or fewer")
    private String email;

    /**
     * 6.6: the maximum is new, and it is not cosmetic.
     *
     * <b>BCrypt silently truncates at 72 bytes.</b> A user who sets a 200-character
     * passphrase gets a password that is really its first 72 bytes, with no error and no
     * warning — and if they later type only the first 72 characters, it authenticates.
     * Rejecting anything longer means what the user typed is what protects the account.
     *
     * A caveat worth knowing: this counts CHARACTERS and BCrypt counts BYTES, so a
     * 72-character password made of multi-byte characters still exceeds 72 bytes and is
     * still truncated. Closing that gap properly means measuring UTF-8 length, which is a
     * custom validator; this constraint covers the realistic case.
     *
     * No complexity rules on purpose. They push people towards "Password1!" — a length
     * floor plus a breach-list check (HaveIBeenPwned's k-anonymity API) is the better
     * trade, and is noted as deferred in PHASE6-CHANGES.md because it needs an outbound
     * call with its own timeout and failure policy.
     */
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;
}
