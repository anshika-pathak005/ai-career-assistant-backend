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
public class DeepSeekService {

    @Value("${nvidia.api.key}")
    private String apiKey;

    @Value("${nvidia.api.url}")
    private String apiUrl;

    @Value("${nvidia.api.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * Analyzes resume using user profile context.
     * Sends both resume text AND user profile to AI for personalized analysis.
     *
     * @param resumeText  cleaned and formatted resume text
     * @param user        full user entity — dream job, skills, experience etc.
     */
    public String analyzeResume(String resumeText, User user) {
        log.info("Sending resume + user profile to DeepSeek for userId: {}", user.getId());

        String requestBody = buildRequestBody(resumeText, user);

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(response);
        } catch (IOException ex) {
            log.error("DeepSeek API connection failed: {}", ex.getMessage());
            throw new GeminiApiException("Failed to connect to AI service. Please try again.");
        }
    }

    private String handleResponse(Response response) throws IOException {
        String responseBody = response.body() != null ? response.body().string() : "";

        switch (response.code()) {
            case 200  -> { return extractContent(responseBody); }
            case 400  -> {
                log.error("Bad request to DeepSeek: {}", responseBody);
                throw new GeminiApiException("Invalid request sent to AI service.");
            }
            case 401  -> {
                log.error("DeepSeek unauthorized — check NVIDIA API key");
                throw new GeminiApiException("AI service authentication failed. Contact support.");
            }
            case 422  -> {
                log.error("DeepSeek unprocessable — content too long or malformed");
                throw new GeminiApiException("Resume content could not be processed. Try a shorter resume.");
            }
            case 429  -> {
                log.error("DeepSeek rate limit hit");
                throw new GeminiApiException("AI service is busy. Please try again in a moment.");
            }
            case 500, 502, 503 -> {
                log.error("DeepSeek server error: {}", response.code());
                throw new GeminiApiException("AI service is temporarily unavailable. Please try again later.");
            }
            default -> {
                log.error("Unexpected DeepSeek response: {} — {}", response.code(), responseBody);
                throw new GeminiApiException("Unexpected error from AI service.");
            }
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root
                    .path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText();

            if (content == null || content.isBlank()) {
                throw new GeminiApiException("AI returned an empty response. Please try again.");
            }

            // strip markdown code fences if present
            content = content
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // validate JSON structure before returning
            JsonNode parsed = objectMapper.readTree(content);

            // make sure both required sections exist
            if (!parsed.has("analysis") || !parsed.has("careerGuidance")) {
                log.error("AI response missing required sections: {}", content);
                throw new GeminiApiException("AI returned incomplete analysis. Please try again.");
            }

            log.info("DeepSeek response validated successfully");
            return content;

        } catch (GeminiApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to parse DeepSeek response: {}", ex.getMessage());
            throw new GeminiApiException("AI returned an unreadable response. Please try again.");
        }
    }

    /**
     * Builds the complete request body.
     *
     * System prompt — strict JSON-only instruction
     * User prompt  — resume text + full user profile for context
     */
    private String buildRequestBody(String resumeText, User user) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(resumeText, user);

        return """
                {
                  "model": "%s",
                  "messages": [
                    {
                      "role": "system",
                      "content": "%s"
                    },
                    {
                      "role": "user",
                      "content": "%s"
                    }
                  ],
                  "max_tokens": 2500,
                  "temperature": 0.1,
                  "response_format": {"type": "json_object"}
                }
                """.formatted(model, escapeJson(systemPrompt), escapeJson(userPrompt));
    }

    /**
     * System prompt — sets strict behavior rules for the AI.
     * Runs before every request. Keeps AI focused and JSON-only.
     */
    private String buildSystemPrompt() {
        return """
                You are an expert career coach and resume analyst with 15+ years of experience in tech hiring.
                
                STRICT RULES — follow every rule exactly:
                1. Return ONLY valid JSON — no explanation, no markdown, no text outside JSON
                2. JSON must have exactly TWO top-level keys: "analysis" and "careerGuidance"
                3. Never return null for array fields — use empty array [] instead
                4. Never return null for string fields — use empty string "" instead
                5. Be honest and realistic in scoring — do not inflate scores
                6. All roadmap and project suggestions must be specific to the dream job and skill gaps
                7. Do not give generic advice — personalize everything based on the user profile provided
                """;
    }

    /**
     * User prompt — the actual resume + user profile context.
     * More context = better and more personalized AI output.
     *
     * Includes:
     * - dream job (most important — drives roadmap and gap analysis)
     * - current skills, experience, industries from user profile
     * - full resume text (actual experience and achievements)
     */
    private String buildUserPrompt(String resumeText, User user) {

        // safely get user profile fields — they may be null for incomplete profiles
        String dreamJob = user.getDreamJob() != null ? user.getDreamJob() : "Not specified";
        String userCategory = user.getUserCategory() != null ? user.getUserCategory() : "Not specified";
        String currentRole = user.getCurrentRole() != null ? user.getCurrentRole() : "Not specified";
        String experienceYears = user.getExperienceYears() != null
                ? user.getExperienceYears().toString() : "0";
        String skills = user.getSkills() != null && !user.getSkills().isEmpty()
                ? String.join(", ", user.getSkills()) : "Not specified";
        String industries = user.getIndustries() != null && !user.getIndustries().isEmpty()
                ? String.join(", ", user.getIndustries()) : "Not specified";

        return """
                Analyze the resume below in context of this user's career goals and profile.
                
                ═══════════════════════════════
                USER CAREER PROFILE
                ═══════════════════════════════
                Dream Job       : %s
                User Category   : %s
                Current Role    : %s
                Experience Years: %s
                Known Skills    : %s
                Target Industries: %s
                ═══════════════════════════════
                
                RESUME TEXT:
                ─────────────────────────────────
                %s
                ─────────────────────────────────
                
                Return ONLY this exact JSON structure:
                
                {
                  "analysis": {
                    "fullName": "extracted from resume",
                    "email": "extracted from resume",
                    "phone": "extracted from resume",
                    "overallScore": <0-100, honest score based on resume quality>,
                    "atsScore": <0-100, based on keywords, formatting, standard sections>,
                    "summary": "<3 sentences: current state, strengths, what's missing for dream job>",
                    "experienceLevel": "<exactly one of: Entry, Mid, Senior>",
                    "experienceYears": <decimal like 2.5>,
                    "currentRole": "most recent job title from resume",
                    "technicalSkills": ["skills found in resume"],
                    "softSkills": ["soft skills found in resume"],
                    "strengths": ["specific strength from THIS resume — not generic"],
                    "improvements": ["specific actionable improvement for THIS resume"],
                    "missingSkills": ["skills needed for dream job but missing from resume"],
                    "missingKeywords": ["ATS keywords missing that recruiters search for"]
                  },
                  "careerGuidance": {
                    "recommendedRoles": ["job titles that match current skills as stepping stones to dream job"],
                    "learningRoadmap": [
                      "Step 1: specific action with resource or timeline",
                      "Step 2: specific action with resource or timeline",
                      "Step 3: ...",
                      "Step 4: ...",
                      "Step 5: ..."
                    ],
                    "projectSuggestions": [
                      "Project 1: specific project idea that fills a skill gap for dream job",
                      "Project 2: ...",
                      "Project 3: ..."
                    ],
                    "careerAdvice": "<3-4 sentences of honest, personalized advice based on their profile and dream job gap>"
                  }
                }
                
                Scoring rules:
                - overallScore below 50 if resume has no measurable achievements
                - atsScore below 60 if resume lacks standard sections or keywords
                - learningRoadmap must be ordered from easiest to hardest
                - projectSuggestions must be buildable within 2-4 weeks each
                - recommendedRoles must be realistic next steps, not the dream job itself
                """.formatted(dreamJob, userCategory, currentRole, experienceYears, skills, industries, resumeText);
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}