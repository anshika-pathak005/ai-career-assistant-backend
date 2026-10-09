package com.careerplatform.career_assistant.ai;

import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.exception.AiProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the AI-generated specializations response into a
 * {@link SpecializationResponse}.
 *
 * Validates the response structure, extracts specialization details,
 * and maps them to the corresponding response DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecializationResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * Parses the AI response and builds a specialization response for the
     * specified career goal.
     *
     * Extracts each specialization's title, description, and core technologies.
     * The response must contain a non-empty specializations array.
     *
     * @param dreamJob the career goal for which specializations were requested
     * @param content the raw JSON content returned by the AI
     * @return a response containing the career goal and its specialization options
     * @throws AiProviderException if the response is invalid JSON or contains
     *         no specialization options
     */
    public SpecializationResponse parse(String dreamJob, String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception ex) {
            log.error("Specializations response was not valid JSON: {}", ex.getMessage());
            throw new AiProviderException("Failed to get specializations. Please try again.", ex);
        }

        JsonNode specs = root.path("specializations");

        // An empty list renders as an empty picker with no explanation, which looks like
        // a frontend bug. Fail here instead so the user gets a retryable error.
        if (!specs.isArray() || specs.isEmpty()) {
            log.error("Specializations response contained no options for: {}", dreamJob);
            throw new AiProviderException("Failed to get specializations. Please try again.");
        }

        List<SpecializationResponse.SpecializationOption> options = new ArrayList<>();

        for (JsonNode spec : specs) {
            List<String> coreTech = new ArrayList<>();
            spec.path("coreTech").forEach(tech -> coreTech.add(tech.asText()));

            options.add(new SpecializationResponse.SpecializationOption(
                    spec.path("title").asText(),
                    spec.path("description").asText(),
                    coreTech));
        }

        return new SpecializationResponse(dreamJob, options);
    }
}
