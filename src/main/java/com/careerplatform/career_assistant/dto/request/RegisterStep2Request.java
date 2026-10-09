package com.careerplatform.career_assistant.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Step 2 registration. Only userCategory and dreamJob are required.
 *
 * 6.6: dreamJob in particular reaches a Groq prompt and a database column, and had no
 * maximum length at all.
 */
@Data
public class RegisterStep2Request {

    @NotBlank(message = "User category is required")
    @Size(max = 50, message = "User category must be 50 characters or fewer")
    private String userCategory;   // Student / Fresher / Professional

    @NotBlank(message = "Dream job is required")
    @Size(max = 100, message = "Dream job must be 100 characters or fewer")
    private String dreamJob;

    @Size(max = 20, message = "Please select 20 industries or fewer")
    private List<@Size(max = 60) String> industries;      // optional — multi-select

    @Size(max = 100, message = "Please list 100 skills or fewer")
    private List<@Size(max = 60) String> skills;          // optional — tags input

    @Min(value = 0, message = "Experience years cannot be negative")
    @Max(value = 60, message = "Experience years must be 60 or fewer")
    private Integer experienceYears;                      // optional

    @Size(max = 100, message = "Current role must be 100 characters or fewer")
    private String currentRole;                           // optional
}
