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
public class ResumeGroqService {

    @Value("${groq.resume.api.key}")
    private String apiKey;

    @Value("${groq.resume.api.url}")
    private String apiUrl;

    @Value("${groq.resume.api.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * Analyzes resume text using user profile as context.
     * Sends resume text + dream job + skills to Groq.
     * Returns validated JSON string with full analysis.
     */
    public String analyzeResume(String resumeText, User user) {
        log.info("Sending resume to Groq for analysis, userId: {}", user.getId());

        String requestBody = buildRequestBody(resumeText, user);

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody,
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(response);
        } catch (IOException ex) {
            log.error("Resume Groq API connection failed: {}", ex.getMessage());
            throw new GeminiApiException("Failed to connect to AI service. Please try again.");
        }
    }

    // ── response handling ─────────────────────────────────────────

    private String handleResponse(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";

        switch (response.code()) {
            case 200  -> { return extractContent(body); }
            case 401  -> throw new GeminiApiException("Resume AI service authentication failed.");
            case 413  -> throw new GeminiApiException("Resume is too large. Please shorten it.");
            case 429  -> throw new GeminiApiException("AI service busy. Please try again shortly.");
            case 500, 502, 503 -> throw new GeminiApiException("AI service temporarily unavailable.");
            default   -> throw new GeminiApiException("Unexpected error from AI service.");
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
                throw new GeminiApiException("AI returned empty response. Please try again.");
            }

            content = content
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // validate JSON
            objectMapper.readTree(content);
            log.info("Resume analysis response validated successfully");
            return content;

        } catch (GeminiApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to parse resume Groq response: {}", ex.getMessage());
            throw new GeminiApiException("AI returned unreadable response. Please try again.");
        }
    }

    // ── request builder ───────────────────────────────────────────

    private String buildRequestBody(String resumeText, User user) {
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
                """.formatted(
                model,
                escapeJson(buildSystemPrompt()),
                escapeJson(buildUserPrompt(resumeText, user))
        );
    }

    // ── prompts ───────────────────────────────────────────────────

    /**
     * System prompt — strict JSON rules + tone.
     * Resume analysis has different rules from roadmap generation.
     */
    private String buildSystemPrompt() {
        return """
                You are a senior technical recruiter and career coach with 15+ years of experience \
                hiring software developers at top tech companies.
                Your job is to analyze resumes critically and honestly — not to be encouraging, \
                but to give real, actionable feedback.
                
                STRICT RULES — follow every rule or return {}:
                1. Return ONLY valid JSON — no explanation, no markdown, no text outside JSON
                2. Be brutally honest — do not inflate scores to encourage the user
                3. All scores are integers between 0 and 100
                4. Always write in second person — "you" and "your", never the candidate's name
                5. strengths must contain exactly 3 specific items from THIS resume
                6. weaknesses must contain exactly 3 specific items from THIS resume
                7. missingSkills must contain 5 to 8 skills — ranked by importance for dream job
                8. improvementSuggestions must contain exactly 5 actionable items
                9. projectSuggestions must contain exactly 3 buildable project ideas
                10. recommendedLearning must contain 4 to 5 specific technologies or concepts
                11. Every item must be specific to this resume and dream job — no generic advice
                12. missingKeywords must be real ATS keywords recruiters search for this role
                13. interviewReadiness must be exactly one of: Beginner, Moderate, Job Ready
                14. summary must be exactly 3 sentences
                15. If you cannot follow this structure exactly, return {}
                """;
    }

    /**
     * User prompt — resume text + full user context.
     * Dream job is the anchor — all analysis is relative to it.
     * More context = more accurate and personalized analysis.
     */
    private String buildUserPrompt(String resumeText, User user) {
        String dreamJob       = user.getDreamJob() != null ? user.getDreamJob() : "Not specified";
        String skills         = user.getSkills() != null && !user.getSkills().isEmpty()
                ? String.join(", ", user.getSkills()) : "Not mentioned";
        Integer experienceLevel = user.getExperienceYears() != null
                ? user.getExperienceYears() : 0;
        String currentRole    = user.getCurrentRole() != null
                ? user.getCurrentRole() : "Not specified";
        String industries     = user.getIndustries() != null && !user.getIndustries().isEmpty()
                ? String.join(", ", user.getIndustries()) : "Not specified";

        return """
                Analyze this resume critically and honestly in context of the user's career goal.
                
                ════════════════════════════════════════
                USER CAREER CONTEXT
                ════════════════════════════════════════
                Dream Job        : %s   ← most important — analyze resume against this
                Current Role     : %s
                Experience Level : %s
                Self-Reported Skills : %s
                Target Industries: %s
                ════════════════════════════════════════
                
                RESUME TEXT:
                ────────────────────────────────────────
                %s
                ────────────────────────────────────────
                
                CRITICAL INSTRUCTIONS:
                - Compare resume skills and projects against what "%s" role actually requires
                - missingSkills = skills a "%s" needs that are NOT in this resume
                - improvementSuggestions = specific things to add or fix in THIS resume
                - projectSuggestions = projects to build that directly fill gaps for "%s"
                - recommendedLearning = technologies to learn next to become ready for "%s"
                - Score honestly — a student resume should NOT score above 70 overall
                - dreamJobMatchSummary = how well this resume positions them for "%s"
                
                Return ONLY this exact JSON — every field required:
                
                {
                  "fullName": "extracted from resume",
                  "email": "extracted from resume",
                  "phone": "extracted from resume",
                  "currentRole": "most recent job title or student status from resume",
                  "experienceLevel": "Entry or Mid or Senior",
                  "experienceYears": <decimal like 1.5 or 0>,
                  "interviewReadiness": "Beginner or Moderate or Job Ready",
                  "dreamJobMatchSummary": "2 sentences — how well resume matches dream job and what is the biggest gap",
                  "summary": "exactly 3 sentences in second person: your current state, your key strength, your biggest gap for dream job",
                  "scores": {
                    "overallScore": <0-100, honest>,
                    "atsScore": <0-100, penalize missing keywords and non-standard formatting>,
                    "technicalSkillsScore": <0-100, based on depth and relevance of technical skills>,
                    "projectQualityScore": <0-100, based on complexity, deployment, real-world relevance>,
                    "formattingScore": <0-100, based on clarity, structure, readability>
                  },
                  "technicalSkills": ["skills found in resume"],
                  "strengths": [
                    "specific strength 1 from THIS resume",
                    "specific strength 2 from THIS resume",
                    "specific strength 3 from THIS resume"
                  ],
                  "weaknesses": [
                    "specific weakness 1 of THIS resume",
                    "specific weakness 2 of THIS resume",
                    "specific weakness 3 of THIS resume"
                  ],
                  "missingSkills": [
                    "most critical skill missing for dream job",
                    "second most critical",
                    "third",
                    "fourth",
                    "fifth — add up to 8 if needed"
                  ],
                  "missingKeywords": ["ATS keyword missing for dream job"],
                  "improvementSuggestions": [
                    "specific improvement 1 for THIS resume",
                    "specific improvement 2",
                    "specific improvement 3",
                    "specific improvement 4",
                    "specific improvement 5"
                  ],
                  "projectSuggestions": [
                    "Project 1: name — description — tech stack — directly fills gap for dream job",
                    "Project 2: name — description — tech stack",
                    "Project 3: name — description — tech stack"
                  ],
                  "recommendedLearning": [
                    "technology or concept 1 — why it matters for dream job",
                    "technology or concept 2 — why it matters",
                    "technology or concept 3 — why it matters",
                    "technology or concept 4 — why it matters"
                  ]
                }
                
                Scoring rules:
                - overallScore: weighted average of all 4 scores
                - atsScore below 60 if standard sections missing or keywords absent
                - technicalSkillsScore based on relevance to dream job, not just quantity
                - projectQualityScore below 50 if no deployment links or real-world projects
                - formattingScore below 70 if resume is hard to scan in 6 seconds
                - Never give overallScore above 80 unless resume is genuinely impressive
                """.formatted(
                dreamJob, currentRole, experienceLevel, skills, industries,
                resumeText,
                dreamJob, dreamJob, dreamJob, dreamJob, dreamJob
        );
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