package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.ai.GroqClient;
import com.careerplatform.career_assistant.ai.GroqRequestBuilder;
import com.careerplatform.career_assistant.ai.PromptTemplateService;
import com.careerplatform.career_assistant.ai.RoadmapResponseParser;
import com.careerplatform.career_assistant.ai.SpecializationResponseParser;
import com.careerplatform.career_assistant.config.properties.GroqProperties;
import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Roadmap and specialization generation.
 *
 * 6.2: this class was 723 lines holding HTTP transport, three prompt templates, response
 * parsing, DTO mapping and JSON escaping. It now does one thing - assemble the values a
 * prompt needs - and delegates the rest:
 *
 * <ul>
 *   <li>{@link GroqClient} - HTTP and response envelope</li>
 *   <li>{@link PromptTemplateService} - templates, loaded from resources/prompts</li>
 *   <li>{@link RoadmapResponseParser}, {@link SpecializationResponseParser} - validation</li>
 * </ul>
 *
 * Two dead private methods went with the split: buildSystemPrompt() - a fourth prompt, for
 * resume analysis, duplicating the one ResumeGroqService actually uses - and
 * buildRequestBody(). Neither had a caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroqService {

    /** Roadmap generation asks for a large completion; specializations is a small one. */
    private static final int ROADMAP_MAX_TOKENS = 7000;
    private static final int SPECIALIZATION_MAX_TOKENS = 800;

    /** Low temperature: these are structured outputs, not creative writing. */
    private static final double ROADMAP_TEMPERATURE = 0.2;
    private static final double SPECIALIZATION_TEMPERATURE = 0.1;

    private final GroqProperties groqProperties;
    private final GroqClient groqClient;
    private final GroqRequestBuilder requestBuilder;
    private final PromptTemplateService prompts;
    private final RoadmapResponseParser roadmapParser;
    private final SpecializationResponseParser specializationParser;

    /**
     * Generates a complete career roadmap.
     * Returns the validated JSON text; RoadmapService turns it into entities and keeps
     * the original string in rawAiResponse.
     */
    public String generateRoadmap(User user, RoadmapGenerationRequest request) {
        log.info("Generating roadmap for userId: {}", user.getId());

        String body = requestBuilder.buildChatRequest(
                groqProperties.api().model(),
                prompts.render("roadmap-system", Map.of()),
                prompts.render("roadmap-user", roadmapValues(user, request)),
                ROADMAP_MAX_TOKENS,
                ROADMAP_TEMPERATURE);

        return roadmapParser.validate(groqClient.complete("roadmap", groqProperties.api(), body));
    }

    /**
     * Returns specialization options for a dream job.
     *
     * This used to duplicate the entire HTTP call inline to bypass the shared transport
     * method's hardcoded validation. It is now the same three steps as every other call.
     */
    public SpecializationResponse getSpecializations(String dreamJob) {
        log.info("Fetching specializations for dream job: {}", dreamJob);

        String body = requestBuilder.buildChatRequest(
                groqProperties.api().model(),
                prompts.render("specializations-system", Map.of()),
                prompts.render("specializations-user", Map.of("dreamJob", dreamJob)),
                SPECIALIZATION_MAX_TOKENS,
                SPECIALIZATION_TEMPERATURE);

        return specializationParser.parse(
                dreamJob, groqClient.complete("specializations", groqProperties.api(), body));
    }

    // -- prompt values ---------------------------------------------------------

    /**
     * Collects every value the roadmap template needs.
     *
     * 6.2: this replaces a 22-argument .formatted() call, which had a real bug in it -
     * see PHASE6-CHANGES.md. A map keyed by name cannot drift out of alignment when a
     * line is added to the template, and PromptTemplateService throws if the template
     * asks for a key that is not here.
     */
    private Map<String, String> roadmapValues(User user, RoadmapGenerationRequest request) {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("dreamJob", orDefault(user.getDreamJob(), "Not specified"));
        values.put("specialization", orDefault(request.getSpecialization(), "Not specified"));
        values.put("userCategory", orDefault(user.getUserCategory(), "Not specified"));
        values.put("currentRole", orDefault(user.getCurrentRole(), "Not specified"));
        values.put("industries", joinOrDefault(user.getIndustries(), "Not specified"));

        values.put("experienceLevel", orDefault(request.getExperienceYears(), "FRESHER"));
        values.put("goalType", orDefault(request.getGoalType(), "GET_JOB"));
        values.put("projectLevel", orDefault(request.getProjectLevel(), "BEGINNER"));
        values.put("learningStyle", orDefault(request.getLearningStyle(), "BALANCED"));
        values.put("weeklyHours", request.getWeeklyHours() != null
                ? request.getWeeklyHours().toString() : "15");

        values.put("skillLevels", formatSkillLevels(request.getSkillLevels()));

        return values;
    }

    /** Renders the skill-level map as one "name: LEVEL" per line. */
    private String formatSkillLevels(Map<String, String> skillLevels) {
        if (skillLevels == null || skillLevels.isEmpty()) {
            return "None provided";
        }

        StringBuilder out = new StringBuilder();
        skillLevels.forEach((skill, level) ->
                out.append(skill).append(": ").append(level).append("\n  "));
        return out.toString();
    }

    private String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String joinOrDefault(List<String> list, String fallback) {
        return (list != null && !list.isEmpty()) ? String.join(", ", list) : fallback;
    }
}
