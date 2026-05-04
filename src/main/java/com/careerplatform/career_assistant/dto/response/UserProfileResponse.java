package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class UserProfileResponse {
    private Long userId;
    private String name;
    private String email;
    private String userCategory;
    private String dreamJob;
    private List<String> industries;
    private List<String> skills;
    private Integer experienceYears;
    private String currentRole;
    private String goalType;
    private String projectLevel;
    private String workPreference;
    private Integer weeklyHours;      // new
//    private String learningStyle;     // new
    private String skillLevels;       // new — JSON string {"React": "Intermediate"}
    private Integer wizardStep;
    private Boolean isProfileComplete;
}