package com.careerplatform.career_assistant.ai;

import com.careerplatform.career_assistant.exception.AiProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates the JSON response returned by the AI for resume analysis.
 *
 * Parses the response and checks that it contains the required "scores"
 * and "summary" sections before passing it to the calling service.
 *
 * Returns the original response content unchanged after validation,
 * allowing the calling service to handle field extraction and further
 * processing.
 *
 * Throws an AiProviderException if the response is invalid JSON or
 * is missing any required section.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * Validates the structure of the resume analysis response.
     *
     * @param content the raw JSON content returned by the AI
     * @return the original content if validation succeeds
     * @throws AiProviderException if the content is invalid JSON or
     *         does not contain the required sections
     */
    public String validate(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(content);
        } catch (Exception ex) {
            log.error("Resume analysis response was not valid JSON: {}", ex.getMessage());
            throw new AiProviderException("AI returned unreadable response. Please try again.", ex);
        }

        if (!parsed.has("scores") || !parsed.has("summary")) {
            log.error("Resume analysis response missing required sections");
            throw new AiProviderException("AI returned an incomplete analysis. Please try again.");
        }

        return content;
    }
}
