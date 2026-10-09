package com.careerplatform.career_assistant.ai;

import com.careerplatform.career_assistant.exception.AiProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates AI-generated roadmap and career analysis responses.
 *
 * Ensures that each response contains the required JSON structure
 * before it is used by the corresponding service.
 *
 * Returns the original JSON content after successful validation so
 * the calling service can process it or preserve it as needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoadmapResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * Validates the structure of an AI-generated roadmap response.
     *
     * Checks that the response contains a summary and a non-empty phases
     * array before returning the original JSON content.
     *
     * @param content the raw JSON content returned by the AI
     * @return the original content if validation succeeds
     * @throws AiProviderException if the content is invalid JSON or does
     *         not contain the required roadmap structure
     */
    public String validate(String content) {
        JsonNode parsed = readJson(content);

        // The system prompt tells the model to return {} if it cannot follow the
        // structure. That instruction is only useful if we actually check for it.
        if (!parsed.has("summary") || !parsed.has("phases")) {
            log.error("AI roadmap response missing required keys; got: {}", fieldNames(parsed));
            throw new AiProviderException("AI returned an incomplete roadmap. Please try again.");
        }

        if (!parsed.path("phases").isArray() || parsed.path("phases").isEmpty()) {
            log.error("AI roadmap response has no phases");
            throw new AiProviderException("AI returned an incomplete roadmap. Please try again.");
        }

        return content;
    }

    /**
     * Validates the structure of an AI-generated career analysis response.
     *
     * Checks that the response contains the required analysis and careerGuidance
     * fields before returning the original JSON content.
     *
     * @param content the raw JSON content returned by the AI
     * @return the original content if validation succeeds
     * @throws AiProviderException if the content is invalid JSON or does
     *         not contain the required fields
     */
    public String validateAnalysis(String content) {
        JsonNode parsed = readJson(content);

        if (!parsed.has("analysis") || !parsed.has("careerGuidance")) {
            log.error("AI analysis response missing required keys; got: {}", fieldNames(parsed));
            throw new AiProviderException("AI returned an incomplete analysis. Please try again.");
        }

        return content;
    }

    /**
     * Parses the supplied content into a JSON tree.
     *
     * @param content the JSON content to parse
     * @return the parsed JSON tree
     * @throws AiProviderException if the content cannot be parsed as JSON
 */
    private JsonNode readJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            log.error("AI response was not valid JSON: {}", ex.getMessage());
            throw new AiProviderException("AI returned unreadable response. Please try again.", ex);
        }
    }

    /**
     * Returns the names of the top-level fields in a JSON object as a
     * comma-separated string for logging.
     *
     * Returns "(none)" if the object contains no fields.
     *
     * @param node the JSON node whose field names should be collected
     * @return a comma-separated list of field names, or "(none)" if empty
     */
    private String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
