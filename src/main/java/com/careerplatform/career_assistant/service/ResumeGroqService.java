package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.ai.GroqClient;
import com.careerplatform.career_assistant.ai.GroqRequestBuilder;
import com.careerplatform.career_assistant.ai.PromptTemplateService;
import com.careerplatform.career_assistant.ai.ResumeResponseParser;
import com.careerplatform.career_assistant.config.properties.GroqProperties;
import com.careerplatform.career_assistant.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resume analysis.
 *
 * Runs against a second Groq account (groq.resume.api.*) so that exhausting the roadmap
 * account's provider-side rate limit does not also take resume analysis down.
 *
 * 6.2: the HTTP call, the status mapping and the envelope unwrapping were all duplicated
 * here from GroqService - including the same choices[0] null-dereference bug. All three
 * now come from GroqClient, so a fix lands in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeGroqService {

    private static final int MAX_TOKENS = 2500;
    private static final double TEMPERATURE = 0.1;

    private final GroqProperties groqProperties;
    private final GroqClient groqClient;
    private final GroqRequestBuilder requestBuilder;
    private final PromptTemplateService prompts;
    private final ResumeResponseParser parser;

    /**
     * Analyses resume text against the user's stated career goal.
     * Returns validated JSON; ResumeProcessingService maps it and clamps the scores.
     */
    public String analyzeResume(String resumeText, User user) {
        log.info("Sending resume to Groq for analysis, userId: {}", user.getId());

        String body = requestBuilder.buildChatRequest(
                groqProperties.resumeApi().model(),
                prompts.render("resume-system", Map.of()),
                prompts.render("resume-user", resumeValues(resumeText, user)),
                MAX_TOKENS,
                TEMPERATURE);

        return parser.validate(groqClient.complete("resume", groqProperties.resumeApi(), body));
    }

    /** Collects the values the resume template needs. */
    private Map<String, String> resumeValues(String resumeText, User user) {
        Map<String, String> values = new LinkedHashMap<>();

        values.put("dreamJob", orDefault(user.getDreamJob(), "Not specified"));
        values.put("currentRole", orDefault(user.getCurrentRole(), "Not specified"));
        values.put("experienceLevel", String.valueOf(
                user.getExperienceYears() != null ? user.getExperienceYears() : 0));
        values.put("skills", joinOrDefault(user.getSkills(), "Not mentioned"));
        values.put("industries", joinOrDefault(user.getIndustries(), "Not specified"));

        /*
         * Resume text is attacker-controlled, so it is wrapped in delimiters and labelled
         * as data rather than instructions.
         *
         * This raises the effort needed to inject instructions; it does not make it
         * impossible. The score range checks in ResumeProcessingService are the defence
         * that actually holds.
         *
         * Note the template places {{resumeText}} inside <resume_content> tags and
         * PromptTemplateService does not re-scan substituted values, so resume text
         * containing "{{dreamJob}}" is inserted literally rather than expanded.
         */
        values.put("resumeText", requestBuilder.wrapUntrusted("RESUME", resumeText));

        return values;
    }

    private String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String joinOrDefault(List<String> list, String fallback) {
        return (list != null && !list.isEmpty()) ? String.join(", ", list) : fallback;
    }
}
