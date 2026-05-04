package com.careerplatform.career_assistant.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

// returned to frontend — user picks one specialization from the list
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecializationResponse {
    private String dreamJob;
    private List<SpecializationOption> specializations;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SpecializationOption {
        private String title;           // "MERN Stack"
        private String description;     // "MongoDB, Express, React, Node.js"
        private List<String> coreTech;  // ["MongoDB", "Express.js", "React", "Node.js"]
    }
}