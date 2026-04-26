package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.GeminiApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GroqService {

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    @Value("${groq.api.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Groq is fast — 30s read timeout is more than enough
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * Resume flow entry point.
     * Sends cleaned resume text + full user profile to Groq.
     * User profile provides context for personalized gap analysis and roadmap.
     */    public String analyzeResume(String resumeText, User user) {
        log.info("Sending resume to Groq for userId: {}", user.getId());
        String requestBody = buildRequestBody(buildSystemPrompt(), buildResumeUserPrompt(resumeText, user));
        return callApi(requestBody);
    }

    /**
     * Form flow entry point — no resume available.
     * Sends only user profile to Groq.
     * Called when user is a fresher or skips resume upload.
     * overallScore and atsScore will be 0 in response since there is nothing to score.
     */    public String analyzeFromProfile(User user) {
        log.info("Sending profile to Groq for userId: {}", user.getId());
        String requestBody = buildRequestBody(buildSystemPrompt(), buildFormUserPrompt(user));
        return callApi(requestBody);
    }

    // ── API call ──────────────────────────────────────────────────────────────

    /**
     * Makes the actual HTTP call to Groq API.
     * Used by both resume and form flows — they only differ in request body.
     * Throws GeminiApiException on any failure — caught by GlobalExceptionHandler.
     */

    private String callApi(String requestBody) {
        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(response);
        } catch (IOException ex) {
            log.error("Groq API connection failed: {}", ex.getMessage());
            throw new GeminiApiException("Failed to connect to AI service. Please try again.");
        }
    }

    /**
     * Maps HTTP response codes to meaningful exceptions.
     * Each status code gets a specific user-friendly message.
     * 200 → extract content, anything else → throw with appropriate message.
     */
    private String handleResponse(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";

        switch (response.code()) {
            case 200  -> { return extractContent(body); }
            case 400  -> {
                log.error("Groq bad request: {}", body);
                throw new GeminiApiException("Invalid request sent to AI service.");
            }
            case 401  -> {
                log.error("Groq unauthorized — check API key");
                throw new GeminiApiException("AI service authentication failed. Contact support.");
            }
            case 413  -> {
                log.error("Groq payload too large — resume text too long");
                throw new GeminiApiException("Resume is too large to process. Please shorten it.");
            }
            case 429  -> {
                log.error("Groq rate limit hit");
                throw new GeminiApiException("AI service is busy. Please try again in a moment.");
            }
            case 500, 502, 503 -> {
                log.error("Groq server error: {}", response.code());
                throw new GeminiApiException("AI service temporarily unavailable. Please try again.");
            }
            default -> {
                log.error("Unexpected Groq response: {} — {}", response.code(), body);
                throw new GeminiApiException("Unexpected error from AI service.");
            }
        }
    }

    /**
     * Extracts the text content from Groq's response structure.
     * Path: choices[0].message.content
     * Also strips markdown fences if Groq wraps JSON in ```json blocks.
     * Validates JSON structure — both "analysis" and "careerGuidance" must exist.
     * Throws if response is empty, malformed, or missing required sections.
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root
                    .path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText();

            if (content == null || content.isBlank()) {
                throw new GeminiApiException("AI returned empty response. Please try again.");
            }

            // strip markdown fences if Groq wraps JSON in ```json ... ```
            content = content
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // validate JSON
            JsonNode parsed = objectMapper.readTree(content);

            // make sure both sections exist
            if (!parsed.has("analysis") || !parsed.has("careerGuidance")) {
                log.error("Groq response missing required sections: {}", content);
                throw new GeminiApiException("AI returned incomplete analysis. Please try again.");
            }

            log.info("Groq response validated successfully");
            return content;

        } catch (GeminiApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to parse Groq response: {}", ex.getMessage());
            throw new GeminiApiException("AI returned unreadable response. Please try again.");
        }
    }

    // ── Request builder ───────────────────────────────────────────────────────

    /**
     * Builds the JSON request body for Groq API.
     * Uses OpenAI-compatible chat format — system + user messages.
     * temperature 0.1 — keeps output consistent and structured, less creative variation.
     * response_format json_object — forces Groq to return valid JSON.
     * max_tokens 2500 — enough for full analysis + roadmap + projects.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) {
        return """
                {
                  "model": "%s",
                  "messages": [
                    {"role": "system", "content": "%s"},
                    {"role": "user", "content": "%s"}
                  ],
                  "max_tokens": 2500,
                  "temperature": 0.1,
                  "response_format": {"type": "json_object"}
                }
                """.formatted(model, escapeJson(systemPrompt), escapeJson(userPrompt));
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

    /**
     * System prompt — defines AI behavior for all requests.
     * Sets strict rules for JSON structure, field counts, tone, and scoring.
     * Instructs AI to return {} if it cannot follow the structure exactly.
     * Runs before every request — keeps AI focused and consistent.
     */
    private String buildSystemPrompt() {
        return """
            You are an expert career coach and resume analyst with 15+ years of experience in tech hiring.
            
            STRICT RULES — follow every rule exactly or return {}:
            1. Return ONLY valid JSON — no explanation, no markdown, no text outside JSON
            2. JSON must have exactly TWO top-level keys: "analysis" and "careerGuidance"
            3. Never return null for any field — use "" for strings, 0 for numbers, [] for arrays
            4. All fields must be present — do not omit any field
            5. Follow exact data types — strings as strings, numbers as numbers, arrays as arrays
            6. Be honest and realistic in scoring — do not inflate scores
            7. All content must be highly specific to this user — absolutely no generic advice
            8. Tone must be direct, second-person — always say "you" and "your", never use the candidate's name
            9. strengths must contain exactly 3 items — specific to what is actually in the resume
            10. improvements must contain exactly 5 items — specific to the gap between current state and dream job
            11. missingSkills must contain 6 to 9 items — ranked by importance for the dream job
            12. projectSuggestions must contain exactly 3 items — each buildable in 2 to 4 weeks
            13. learningRoadmap must contain exactly 5 steps — ordered beginner to advanced, each specific and actionable
            14. Every suggestion must be directly aligned with the user's dream job — not general career advice
            15. Every field — technicalSkills, softSkills, strengths, improvements, missingSkills — must be extracted strictly and thoroughly from the actual resume or profile text provided. Do not skip, assume, or generalize any skill or trait that is explicitly mentioned. If it is written in the resume, it must appear in the response
            16. If you cannot follow this structure exactly, return {} and do not attempt to explain or return partial information. The structure and data types must be exactly as specified, with no deviations.
            """;
    }

    /**
     * User prompt for resume-based analysis.
     * Combines user career profile + resume text into one structured prompt.
     * Profile section tells AI where the user wants to go (dream job, goals).
     * Resume section tells AI where the user is now.
     * AI compares both to generate gap analysis and personalized roadmap.
     */
    private String buildResumeUserPrompt(String resumeText, User user) {
        String dreamJob        = safe(user.getDreamJob(), "Not specified");
        String userCategory    = safe(user.getUserCategory(), "Not specified");
        String currentRole     = safe(user.getCurrentRole(), "Not specified");
        String experienceYears = user.getExperienceYears() != null ? user.getExperienceYears().toString() : "0";
        String skills          = safeList(user.getSkills(), "Not specified");
        String industries      = safeList(user.getIndustries(), "Not specified");
        String goalType        = safe(user.getGoalType(), "Not specified");
        String projectLevel    = safe(user.getProjectLevel(), "Not specified");
        String workPreference  = safe(user.getWorkPreference(), "Not specified");

        return """
            Analyze this resume using the user's career profile as full context.
            The dream job is the most important field — every suggestion must help the user reach it.
            
            ═══════════════════════════════════════
            USER CAREER PROFILE (use this as context)
            ═══════════════════════════════════════
            Dream Job         : %s        ← most important — all suggestions must target this
            User Category     : %s
            Current Role      : %s
            Experience Years  : %s
            Self-Reported Skills : %s
            Target Industries : %s
            Goal Type         : %s
            Project Level     : %s
            Work Preference   : %s
            ═══════════════════════════════════════
            
            RESUME TEXT:
            ───────────────────────────────────────
            %s
            ───────────────────────────────────────
            
            IMPORTANT INSTRUCTIONS:
            - Compare resume skills with what is required for the dream job
            - missingSkills = skills needed for "%s" that are NOT in the resume
            - improvements = specific actions to close the gap toward "%s"
            - projectSuggestions = projects that directly build skills for "%s"
            - learningRoadmap = ordered path from current level to "%s" ready
            - recommendedRoles = realistic stepping stone roles toward "%s"
            - Always write in second person — say "you" and "your", never use the candidate's name
            
            Return ONLY this exact JSON — every field required:
            
            {
              "analysis": {
                "fullName": "string — from resume",
                "email": "string — from resume",
                "phone": "string — from resume",
                "overallScore": <integer 0-100, penalize if no measurable achievements>,
                "atsScore": <integer 0-100, penalize missing keywords and non-standard formatting>,
                "summary": "exactly 3 sentences in second person: your current state, your key strength, your biggest gap toward dream job",
                "experienceLevel": "Entry or Mid or Senior",
                "experienceYears": <decimal like 1.5>,
                "currentRole": "string — most recent role from resume",
                "technicalSkills": ["skills found in resume"],
                "softSkills": ["soft skills found in resume"],
                "strengths": [
                  "specific strength 1 from this resume",
                  "specific strength 2 from this resume",
                  "specific strength 3 from this resume"
                ],
                "improvements": [
                  "specific improvement 1 needed to reach dream job",
                  "specific improvement 2 needed to reach dream job",
                  "specific improvement 3 needed to reach dream job",
                  "specific improvement 4 needed to reach dream job",
                  "specific improvement 5 needed to reach dream job"
                ],
                "missingSkills": [
                  "most critical skill missing for dream job",
                  "second most critical",
                  "third",
                  "fourth",
                  "fifth",
                  "sixth — add up to 9 if needed"
                ],
                "missingKeywords": ["ATS keyword missing for dream job role"]
              },
              "careerGuidance": {
                "recommendedRoles": ["realistic stepping stone role 1", "role 2", "role 3"],
                "learningRoadmap": [
                  "Step 1: beginner action with specific resource or timeline",
                  "Step 2: next action",
                  "Step 3: intermediate action",
                  "Step 4: advanced action",
                  "Step 5: final step to be ready for dream job"
                ],
                "projectSuggestions": [
                  "Project 1: specific project that fills a skill gap for dream job — buildable in 2 to 4 weeks",
                  "Project 2: another project",
                  "Project 3: another project"
                ],
                "careerAdvice": "3 to 4 sentences in second person, honest and personalized advice for reaching dream job from current state"
              }
            }
            
            Scoring rules:
            - overallScore below 50 if no measurable achievements in resume
            - atsScore below 60 if standard sections missing or dream job keywords absent
            - Do not give high scores just to be encouraging — be realistic
            """.formatted(
                dreamJob, userCategory, currentRole, experienceYears,
                skills, industries, goalType, projectLevel, workPreference,
                resumeText,
                dreamJob, dreamJob, dreamJob, dreamJob, dreamJob
        );
    }

    /**
     * User prompt for form-based analysis — no resume.
     * Uses only user profile fields to generate career guidance.
     * Explicitly tells AI scores must be 0 since there is no resume to evaluate.
     * Skills section uses only what user has explicitly mentioned — no assumptions.
     */
    private String buildFormUserPrompt(User user) {
        String name            = safe(user.getName(), "");
        String email           = safe(user.getEmail(), "");
        String dreamJob        = safe(user.getDreamJob(), "Not specified");
        String userCategory    = safe(user.getUserCategory(), "Not specified");
        String currentRole     = safe(user.getCurrentRole(), "Not specified");
        String experienceYears = user.getExperienceYears() != null ? user.getExperienceYears().toString() : "0";
        String skills          = safeList(user.getSkills(), "None mentioned");
        String industries      = safeList(user.getIndustries(), "Not specified");
        String goalType        = safe(user.getGoalType(), "Not specified");
        String projectLevel    = safe(user.getProjectLevel(), "Not specified");
        String workPreference  = safe(user.getWorkPreference(), "Not specified");

        return """
            This user has NO resume. Generate career analysis and guidance from profile only.
            The dream job is the most important field — every suggestion must help the user reach it.
            
            ═══════════════════════════════════════
            USER CAREER PROFILE
            ═══════════════════════════════════════
            Name              : %s
            Email             : %s
            Dream Job         : %s        ← most important — all suggestions must target this
            User Category     : %s
            Current Role      : %s
            Experience Years  : %s
            Self-Reported Skills : %s
            Target Industries : %s
            Goal Type         : %s
            Project Level     : %s
            Work Preference   : %s
            ═══════════════════════════════════════
            
            IMPORTANT INSTRUCTIONS:
            - No resume available — base everything only on the profile above
            - overallScore and atsScore must both be 0 — nothing to score
            - missingSkills = skills needed for "%s" that the user has NOT mentioned
            - improvements = what you need to do to reach "%s"
            - projectSuggestions = projects that directly build skills for "%s"
            - learningRoadmap = ordered path from current level to "%s" ready
            - Always write in second person — say "you" and "your", never use the user's name
            
            Return ONLY this exact JSON — every field required:
            
            {
              "analysis": {
                "fullName": "%s",
                "email": "%s",
                "phone": "",
                "overallScore": 0,
                "atsScore": 0,
                "summary": "exactly 3 sentences in second person: your current state, what you have, your biggest gap toward dream job",
                "experienceLevel": "Entry or Mid or Senior",
                "experienceYears": %s,
                "currentRole": "%s",
                "technicalSkills": [<only skills the user explicitly mentioned>],
                "softSkills": [],
                "strengths": [
                  "strength 1 based on what user has",
                  "strength 2",
                  "strength 3"
                ],
                "improvements": [
                  "specific improvement 1 to reach dream job",
                  "specific improvement 2",
                  "specific improvement 3",
                  "specific improvement 4",
                  "specific improvement 5"
                ],
                "missingSkills": [
                  "most critical skill missing for dream job",
                  "second most critical",
                  "third",
                  "fourth",
                  "fifth",
                  "sixth — add up to 9 if needed"
                ],
                "missingKeywords": ["ATS keyword for dream job role"]
              },
              "careerGuidance": {
                "recommendedRoles": ["realistic stepping stone toward dream job", "role 2", "role 3"],
                "learningRoadmap": [
                  "Step 1: beginner action with specific resource or timeline",
                  "Step 2: next action",
                  "Step 3: intermediate action",
                  "Step 4: advanced action",
                  "Step 5: final step to be ready for dream job"
                ],
                "projectSuggestions": [
                  "Project 1: specific project for dream job skill gap — buildable in 2 to 4 weeks",
                  "Project 2: another project",
                  "Project 3: another project"
                ],
                "careerAdvice": "3 to 4 sentences in second person, honest personalized advice for reaching dream job from current level"
              }
            }
            """.formatted(
                name, email, dreamJob, userCategory, currentRole, experienceYears,
                skills, industries, goalType, projectLevel, workPreference,
                dreamJob, dreamJob, dreamJob, dreamJob,
                name, email, experienceYears, currentRole
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Null-safe string helper.
     * Returns value if non-null and non-blank, otherwise returns fallback.
     */
    private String safe(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Null-safe list helper.
     * Joins list items with comma if list exists and is non-empty.
     * Returns fallback string if list is null or empty.
     */
    private String safeList(java.util.List<String> list, String fallback) {
        return (list != null && !list.isEmpty()) ? String.join(", ", list) : fallback;
    }

    /**
     * Escapes special characters for safe JSON string embedding.
     * Prevents prompt content from breaking the JSON request body.
     * Handles: backslash, quotes, newlines, carriage returns, tabs.
     */
    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}