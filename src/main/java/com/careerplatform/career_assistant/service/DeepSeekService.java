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
            
            STRICT RULES — follow every rule exactly or return {}:
            1. Return ONLY valid JSON — no explanation, no markdown, no text outside JSON
            2. JSON must have exactly TWO top-level keys: "analysis" and "careerGuidance"
            3. Never return null for any field — use "" for strings, 0 for numbers, [] for arrays
            4. All fields must be present — do not omit any field from the structure
            5. Follow exact data types — strings as strings, numbers as numbers, arrays as arrays
            6. Be honest and realistic in scoring — do not inflate scores
            7. All content must be specific to this user's profile — no generic advice
            8. Tone must be professional, honest, and constructive
            9. strengths must contain exactly 3 items
            10. improvements must contain exactly 3 items
            11. missingSkills must contain 4 to 6 items — most important ones only, prioritized by impact
            12. projectSuggestions must contain exactly 3 items — each buildable in 2 to 4 weeks
            13. learningRoadmap must contain exactly 5 steps — ordered beginner to advanced, each specific and actionable
            14. If you cannot follow this structure exactly, return {}
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

        String dreamJob       = user.getDreamJob() != null ? user.getDreamJob() : "Not specified";
        String userCategory   = user.getUserCategory() != null ? user.getUserCategory() : "Not specified";
        String currentRole    = user.getCurrentRole() != null ? user.getCurrentRole() : "Not specified";
        String experienceYears = user.getExperienceYears() != null ? user.getExperienceYears().toString() : "0";
        String skills         = user.getSkills() != null && !user.getSkills().isEmpty()
                ? String.join(", ", user.getSkills()) : "Not specified";
        String industries     = user.getIndustries() != null && !user.getIndustries().isEmpty()
                ? String.join(", ", user.getIndustries()) : "Not specified";
        String goalType       = user.getGoalType() != null ? user.getGoalType() : "Not specified";
        String projectLevel   = user.getProjectLevel() != null ? user.getProjectLevel() : "Not specified";
        String workPreference = user.getWorkPreference() != null ? user.getWorkPreference() : "Not specified";

        return """
            Analyze the resume below in full context of this user's career goals and profile.
            
            ═══════════════════════════════
            USER CAREER PROFILE
            ═══════════════════════════════
            Dream Job        : %s
            User Category    : %s
            Current Role     : %s
            Experience Years : %s
            Known Skills     : %s
            Target Industries: %s
            Goal Type        : %s
            Project Level    : %s
            Work Preference  : %s
            ═══════════════════════════════
            
            RESUME TEXT:
            ─────────────────────────────────
            %s
            ─────────────────────────────────
            
            Return ONLY this exact JSON — every field must be present, no omissions:
            
            {
              "analysis": {
                "fullName": "string — full name from resume",
                "email": "string — email from resume",
                "phone": "string — phone from resume",
                "overallScore": <integer 0-100 — honest score, penalize missing achievements>,
                "atsScore": <integer 0-100 — penalize missing keywords and non-standard formatting>,
                "summary": "string — exactly 3 sentences: current state, key strength, biggest gap vs dream job",
                "experienceLevel": "string — exactly one of: Entry, Mid, Senior",
                "experienceYears": <decimal number — e.g. 2.5>,
                "currentRole": "string — most recent job title from resume",
                "technicalSkills": ["string", "..."],
                "softSkills": ["string", "..."],
                "strengths": ["string", "string", "string"],
                "improvements": ["string", "string", "string"],
                "missingSkills": ["string", "string", "string", "string"],
                "missingKeywords": ["string", "..."]
              },
              "careerGuidance": {
                "recommendedRoles": ["string", "..."],
                "learningRoadmap": [
                  "Step 1: string",
                  "Step 2: string",
                  "Step 3: string",
                  "Step 4: string",
                  "Step 5: string"
                ],
                "projectSuggestions": [
                  "Project 1: string",
                  "Project 2: string",
                  "Project 3: string"
                ],
                "careerAdvice": "string — 3 to 4 sentences, personalized, honest, actionable"
              }
            }
            
            Scoring rules:
            - overallScore below 50 if resume has no measurable achievements or is too short
            - atsScore below 60 if standard sections are missing or keywords are absent
            - strengths: exactly 3, specific to THIS resume — not generic
            - improvements: exactly 3, actionable, specific to dream job gap
            - missingSkills: 4 to 6, ranked by importance for dream job
            - learningRoadmap: exactly 5 steps, beginner to advanced, each with a specific action or resource
            - projectSuggestions: exactly 3, each buildable in 2 to 4 weeks, directly fills a skill gap
            - recommendedRoles: realistic stepping stones — not the dream job itself
            """.formatted(dreamJob, userCategory, currentRole, experienceYears,
                skills, industries, goalType, projectLevel, workPreference, resumeText);
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Form-based analysis — no resume text available.
     * Uses ONLY user profile to generate career guidance.
     * Called when user has no resume (fresher flow).
     */
    public String analyzeFromProfile(User user) {
        log.info("Sending user profile to DeepSeek for form-based analysis, userId: {}", user.getId());

        String requestBody = buildFormRequestBody(user);
        System.out.println("Form-based analysis request body: " + requestBody); // debug log

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

    /**
     * Request body for form-based analysis.
     * No resume text — AI generates guidance purely from profile.
     */
    private String buildFormRequestBody(User user) {
        String systemPrompt = buildSystemPrompt(); // reuse same system prompt

        String dreamJob = user.getDreamJob() != null ? user.getDreamJob() : "Not specified";
        String userCategory = user.getUserCategory() != null ? user.getUserCategory() : "Not specified";
        String currentRole = user.getCurrentRole() != null ? user.getCurrentRole() : "Not specified";
        String experienceYears = user.getExperienceYears() != null
                ? user.getExperienceYears().toString() : "0";
        String skills = user.getSkills() != null && !user.getSkills().isEmpty()
                ? String.join(", ", user.getSkills()) : "None mentioned";
        String industries = user.getIndustries() != null && !user.getIndustries().isEmpty()
                ? String.join(", ", user.getIndustries()) : "Not specified";
        String goalType = user.getGoalType() != null ? user.getGoalType() : "Not specified";
        String projectLevel = user.getProjectLevel() != null ? user.getProjectLevel() : "Not specified";
        String workPreference = user.getWorkPreference() != null ? user.getWorkPreference() : "Not specified";

        String userPrompt = """
        This user has NO resume. Generate career analysis and guidance based on profile only.
        
        ═══════════════════════════════
        USER CAREER PROFILE
        ═══════════════════════════════
        Name             : %s
        Email            : %s
        Dream Job        : %s
        User Category    : %s
        Current Role     : %s
        Experience Years : %s
        Known Skills     : %s
        Target Industries: %s
        Goal Type        : %s
        Project Level    : %s
        Work Preference  : %s
        ═══════════════════════════════
        
        No resume available — base everything on the profile above.
        For analysis section: overallScore and atsScore must be 0 since there is no resume.
        For skills: use only what the user has explicitly mentioned.
        
        Return ONLY this exact JSON — every field must be present, no omissions:
        
        {
          "analysis": {
            "fullName": "%s",
            "email": "%s",
            "phone": "",
            "overallScore": 0,
            "atsScore": 0,
            "summary": "string — exactly 3 sentences: their current state, what they have, what they need for dream job",
            "experienceLevel": "string — exactly one of: Entry, Mid, Senior",
            "experienceYears": %s,
            "currentRole": "%s",
            "technicalSkills": [<only skills user mentioned>],
            "softSkills": [],
            "strengths": ["string", "string", "string"],
            "improvements": ["string", "string", "string"],
            "missingSkills": ["string", "string", "string", "string"],
            "missingKeywords": ["string", "..."]
          },
          "careerGuidance": {
            "recommendedRoles": ["string", "..."],
            "learningRoadmap": [
              "Step 1: string",
              "Step 2: string",
              "Step 3: string",
              "Step 4: string",
              "Step 5: string"
            ],
            "projectSuggestions": [
              "Project 1: string",
              "Project 2: string",
              "Project 3: string"
            ],
            "careerAdvice": "string — 3 to 4 sentences, personalized, honest, actionable"
          }
        }
        
        Rules:
        - strengths: exactly 3, based on skills and profile they provided
        - improvements: exactly 3, most impactful actions to reach dream job
        - missingSkills: 4 to 6, ranked by importance for dream job
        - learningRoadmap: exactly 5 steps, beginner to advanced, specific and actionable
        - projectSuggestions: exactly 3, buildable in 2 to 4 weeks, fills skill gaps for dream job
        - recommendedRoles: realistic next steps toward dream job, not the dream job itself
        """.formatted(
                user.getName() != null ? user.getName() : "",
                user.getEmail() != null ? user.getEmail() : "",
                dreamJob, userCategory, currentRole, experienceYears,
                skills, industries, goalType, projectLevel, workPreference,
                user.getName() != null ? user.getName() : "",
                user.getEmail() != null ? user.getEmail() : "",
                experienceYears, currentRole
        );

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
}