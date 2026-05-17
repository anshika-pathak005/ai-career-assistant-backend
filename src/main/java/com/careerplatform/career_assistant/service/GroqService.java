package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.RoadmapGenerationRequest;
import com.careerplatform.career_assistant.dto.response.SpecializationResponse;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.GeminiApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

// Check what type of response this is
            boolean isValidResumeResponse = parsed.has("analysis") && parsed.has("careerGuidance");
            boolean isValidRoadmapResponse = parsed.has("summary") && parsed.has("phases");

            if (!isValidResumeResponse && !isValidRoadmapResponse) {
                log.error("AI response missing required sections. Has fields: {}",
                        parsed.fieldNames());
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
     * Generates complete career roadmap for user.
     * Takes full user profile + roadmap form preferences.
     * Returns structured JSON with phases, skills, steps.
     */
    public String generateRoadmap(User user, RoadmapGenerationRequest request) {
        log.info("Generating roadmap for userId: {}", user.getId());
        String requestBody = buildRoadmapRequestBody(user, request);
        return callApi(requestBody);
    }

    /**
     * Builds roadmap generation request body.
     * Separate from resume analysis — different prompt, different output structure.
     */
    private String buildRoadmapRequestBody(User user, RoadmapGenerationRequest request) {
        String systemPrompt = buildRoadmapSystemPrompt();
        String userPrompt = buildRoadmapUserPrompt(user, request);

        return """
            {
              "model": "%s",
              "messages": [
                {"role": "system", "content": "%s"},
                {"role": "user", "content": "%s"}
              ],
              "max_tokens": 7000,
              "temperature": 0.2,
              "response_format": {"type": "json_object"}
            }
            """.formatted(model, escapeJson(systemPrompt), escapeJson(userPrompt));
    }

    private String buildRoadmapSystemPrompt() {
        return """
        You are an expert technical curriculum designer with 10+ years of experience building job-ready roadmaps.
        
        STRICT RULES — follow every rule exactly:
        1. Return ONLY valid JSON — no markdown, no explanations, no text outside JSON
        2. JSON must have: summary, totalEstimatedWeeks, phases
        3. totalEstimatedWeeks = based on weeklyHours (15 hours/week = 20-24 weeks)
        4. Must have EXACTLY 5 phases in this order:
           Phase 1: Foundation (Beginner level skills)
           Phase 2: Core Intermediate (Mid-level skills)
           Phase 3: Advanced Concepts (Senior-level skills)
           Phase 4: Tools & Best Practices (Git, Docker, Testing, CI/CD)
           Phase 5: Portfolio Projects (5 projects minimum)
        
        5. Phase 1-4: each has 3-4 skills
        6. Phase 5: exactly 5 projects (skillType: PROJECT)
        
        7. CRITICAL: Steps must be SPECIFIC TOPICS, not generic actions
           WRONG: "Set up environment", "Practice exercises", "Review skills"
           CORRECT: "Variables & Data Types", "Functions & Scope", "Components & Props"
        
        8. Each skill in Phase 1-4 must have exactly 6-8 steps
        9. Each project in Phase 5 must have 5-7 milestone steps
        10. Each step must have: title, description, estimatedHours (2-8 hours)
        
        11. RESOURCE RULES - VERY IMPORTANT:
            - Each skill and project must have exactly 3 resources
            - Each resource must have: name, url, type
            - URLs must be REAL, WORKING, and FREE
            - NEVER invent fake URLs like "https://example.com"
            - URL types: "DOCUMENTATION", "COURSE", "VIDEO", "ARTICLE", "PRACTICE"
            
            Valid FREE resource URLs:
            - Documentation: https://developer.mozilla.org/, https://docs.python.org/, https://react.dev/
            - Courses: https://www.freecodecamp.org/, https://www.w3schools.com/
            - Videos: https://youtu.be/ (specific YouTube video)
            - Practice: https://leetcode.com/, https://www.hackerrank.com/
            
            Example:
            "resources": [
              {"name": "MDN Web Docs - JavaScript", "url": "https://developer.mozilla.org/en-US/docs/Web/JavaScript", "type": "DOCUMENTATION"},
              {"name": "freeCodeCamp - JS Algorithms", "url": "https://www.freecodecamp.org/learn/javascript-algorithms-and-data-structures/", "type": "COURSE"},
              {"name": "JavaScript Crash Course", "url": "https://youtu.be/PkZNo7MFNFg", "type": "VIDEO"}
            ]
        
        12. If you cannot follow this structure exactly, return {}
        """;
    }

    private String buildRoadmapUserPrompt(User user, RoadmapGenerationRequest request) {
        String dreamJob = safe(user.getDreamJob(), "Not specified");
        String specialization = request.getSpecialization() != null ? request.getSpecialization() : "Not specified";
        String userCategory = safe(user.getUserCategory(), "Not specified");
        String currentRole = safe(user.getCurrentRole(), "Not specified");
        String industries = safeList(user.getIndustries(), "Not specified");
        String experienceLevel = request.getExperienceYears() != null ? request.getExperienceYears() : "FRESHER";
        String goalType = request.getGoalType() != null ? request.getGoalType() : "GET_JOB";
        String projectLevel = request.getProjectLevel() != null ? request.getProjectLevel() : "BEGINNER";
        String learningStyle = request.getLearningStyle() != null ? request.getLearningStyle() : "BALANCED";
        String weeklyHours = request.getWeeklyHours() != null ? request.getWeeklyHours().toString() : "15";

        StringBuilder skillLevelsStr = new StringBuilder();
        if (request.getSkillLevels() != null) {
            request.getSkillLevels().forEach((skill, level) ->
                    skillLevelsStr.append(skill).append(": ").append(level).append("\n  ")
            );
        }

        return """
                Generate a complete personalized career roadmap for: "%s" with specialization "%s"
                        
                ═══════════════════════════════════════════════
                USER PROFILE
                ═══════════════════════════════════════════════
                Dream Job        : %s
                Specialization   : %s
                User Category    : %s
                Current Role     : %s
                Experience Level : %s
                Target Industries: %s
                ═══════════════════════════════════════════════
                ROADMAP PREFERENCES
                ═══════════════════════════════════════════════
                Goal Type        : %s
                Project Level    : %s
                Learning Style   : %s
                Weekly Hours     : %s hours/week
                ═══════════════════════════════════════════════
                CURRENT SKILLS AND LEVELS
                ═══════════════════════════════════════════════
                %s
                ═══════════════════════════════════════════════
                        
                ================================================
                DOMAIN DETECTION (Auto-adjust based on dream job)
                ================================================
                        
                Based on dream job "%s", identify the domain and generate relevant skills:
                        
                For "Full Stack" or "Web Developer":
                - Frontend: HTML, CSS, JavaScript, React/Angular/Vue
                - Backend: Node.js/Python/Java, Express/Django/Spring Boot
                - Database: SQL, MongoDB/PostgreSQL
                - Tools: Git, Docker, Testing, Deployment
                - Projects: 5 web applications (Task Manager, E-commerce, Blog, Social Media, Capstone)
                        
                For "Data Scientist" or "Data Analyst":
                - Python (Pandas, NumPy, Matplotlib)
                - SQL for data extraction
                - Statistics & Probability
                - Machine Learning (scikit-learn, TensorFlow)
                - Data Visualization (Tableau, Power BI)
                - Projects: 5 data projects (EDA, Predictive Modeling, Clustering, NLP, Dashboard)
                        
                For "DevOps" or "Cloud Engineer":
                - Linux & Shell Scripting
                - CI/CD (Jenkins, GitHub Actions)
                - Containerization (Docker, Kubernetes)
                - Cloud (AWS/Azure/GCP)
                - Infrastructure as Code (Terraform)
                - Projects: 5 DevOps projects (CI/CD Pipeline, Containerized App, K8s Deployment, Monitoring Stack, IaC)
                        
                For "Mobile Developer":
                - Platform (iOS: Swift, Android: Kotlin, Cross: Flutter/React Native)
                - UI/UX principles for mobile
                - State management
                - API integration
                - App store deployment
                - Projects: 5 mobile apps (Weather, Task Manager, Chat, E-commerce, Social Media)
                        
                For "AI/ML Engineer":
                - Python fundamentals
                - Data preprocessing
                - ML algorithms (regression, classification, clustering)
                - Deep Learning (Neural Networks)
                - MLOps (model deployment)
                - Projects: 5 AI projects (Predictive Model, Image Classification, NLP, Recommendation, LLM App)
                        
                ================================================
                STEP TOPIC EXAMPLES (Follow this pattern)
                ================================================
                        
                For Programming Language (Python/Java/JavaScript):
                ✓ "Variables & Data Types"
                ✓ "Control Flow: if-else, loops"
                ✓ "Functions & Scope"
                ✓ "Data Structures: Lists, Dictionaries, Objects"
                ✓ "Error Handling & Debugging"
                ✓ "File I/O Operations"
                        
                For Frontend Framework (React/Angular/Vue):
                ✓ "Components & Props"
                ✓ "State Management with Hooks"
                ✓ "Event Handling & Forms"
                ✓ "Routing & Navigation"
                ✓ "API Integration (fetch/axios)"
                ✓ "Performance Optimization"
                        
                For Backend Framework (Express/Django/Spring):
                ✓ "Setting up routes & endpoints"
                ✓ "Middleware & Request handling"
                ✓ "Database integration (ORM/ODM)"
                ✓ "Authentication & JWT"
                ✓ "Error handling & logging"
                ✓ "API documentation"
                        
                For Database (SQL/MongoDB):
                ✓ "CRUD operations"
                ✓ "Schema/Model design"
                ✓ "Querying & filtering"
                ✓ "Relationships & Joins"
                ✓ "Indexing for performance"
                ✓ "Aggregation pipelines"
                        
                For Tools (Git/Docker):
                ✓ "git init, add, commit workflow"
                ✓ "Branching & merging strategies"
                ✓ "Remote repositories (push, pull, clone)"
                ✓ "Dockerfile & container basics"
                ✓ "Docker Compose for multi-container"
                ✓ "CI/CD pipeline setup"
                        
                ================================================
                RESOURCE FORMAT (MUST FOLLOW EXACTLY)
                ================================================
                        
                Each skill and project must have "resources" array with EXACTLY 3 items.
                Each item must have this structure:
                        
                {
                  "name": "Descriptive name of resource",
                  "url": "https://actual-working-url.com",
                  "type": "DOCUMENTATION" or "COURSE" or "VIDEO" or "ARTICLE" or "PRACTICE"
                }
                        
                REAL RESOURCE EXAMPLES:
                        
                HTML/CSS Resources:
                - {"name": "MDN HTML Reference", "url": "https://developer.mozilla.org/en-US/docs/Web/HTML", "type": "DOCUMENTATION"}
                - {"name": "freeCodeCamp Responsive Design", "url": "https://www.freecodecamp.org/learn/2022/responsive-web-design/", "type": "COURSE"}
                - {"name": "CSS Flexbox Tutorial", "url": "https://youtu.be/JJSoEo8JSnc", "type": "VIDEO"}
                        
                JavaScript Resources:
                - {"name": "MDN JavaScript Guide", "url": "https://developer.mozilla.org/en-US/docs/Web/JavaScript", "type": "DOCUMENTATION"}
                - {"name": "JavaScript.info", "url": "https://javascript.info/", "type": "COURSE"}
                - {"name": "LeetCode Practice", "url": "https://leetcode.com/problemset/javascript/", "type": "PRACTICE"}
                        
                React Resources:
                - {"name": "React Official Docs", "url": "https://react.dev/learn", "type": "DOCUMENTATION"}
                - {"name": "React Tutorial for Beginners", "url": "https://youtu.be/SqcY0GlETPk", "type": "VIDEO"}
                - {"name": "Scrimba React Course", "url": "https://scrimba.com/learn/learnreact", "type": "COURSE"}
                        
                ================================================
                        
                Return ONLY this exact JSON structure:
                        
                {
                  "summary": "2-3 sentence personalized summary for becoming a %s",
                  "totalEstimatedWeeks": <integer based on weeklyHours: weeklyHours = %s, calculate total weeks>,
                  "phases": [
                    {
                      "title": "Phase 1: Foundation",
                      "description": "Build fundamental skills for %s",
                      "estimatedWeeks": <integer>,
                      "skills": [
                        {
                          "skillName": "Specific skill name",
                          "skillType": "SKILL",
                          "isExistingSkill": <true/false based on user's current skills>,
                          "startingLevel": "BEGINNER",
                          "targetLevel": "INTERMEDIATE",
                          "estimatedDays": <integer 7-21>,
                          "resources": [
                            {"name": "...", "url": "...", "type": "..."},
                            {"name": "...", "url": "...", "type": "..."},
                            {"name": "...", "url": "...", "type": "..."}
                          ],
                          "steps": [
                            {
                              "title": "Specific topic title",
                              "description": "What to learn in this step",
                              "estimatedHours": <integer 2-8>
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "title": "Phase 2: Core Intermediate",
                      "description": "Build mid-level skills for %s",
                      "estimatedWeeks": <integer>,
                      "skills": [ ... same structure ... ]
                    },
                    {
                      "title": "Phase 3: Advanced Concepts",
                      "description": "Master advanced topics for %s",
                      "estimatedWeeks": <integer>,
                      "skills": [ ... same structure ... ]
                    },
                    {
                      "title": "Phase 4: Tools & Best Practices",
                      "description": "Learn essential development tools",
                      "estimatedWeeks": <integer>,
                      "skills": [
                        {
                          "skillName": "Git & GitHub",
                          "skillType": "TOOL",
                          "isExistingSkill": false,
                          "startingLevel": "BEGINNER",
                          "targetLevel": "INTERMEDIATE",
                          "estimatedDays": 7,
                          "resources": [...],
                          "steps": [...]
                        },
                        {
                          "skillName": "Docker Basics",
                          "skillType": "TOOL",
                          ...
                        },
                        {
                          "skillName": "Testing (Jest/React Testing Library)",
                          "skillType": "TOOL",
                          ...
                        }
                      ]
                    },
                    {
                      "title": "Phase 5: Portfolio Projects",
                      "description": "Build job-ready projects",
                      "estimatedWeeks": <integer>,
                      "skills": [
                        {
                          "skillName": "Real Project Name 1",
                          "skillType": "PROJECT",
                          "projectDescription": "What this project does and what you will learn",
                          "techStack": ["tech1", "tech2", "tech3", "tech4", "tech5"],
                          "estimatedDays": <integer 10-21>,
                          "resources": [
                            {"name": "...", "url": "...", "type": "..."},
                            {"name": "...", "url": "...", "type": "..."},
                            {"name": "...", "url": "...", "type": "..."}
                          ],
                          "steps": [
                            {
                              "title": "Milestone title",
                              "description": "Specific development phase",
                              "estimatedHours": <integer 4-12>
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                        
                IMPORTANT REMINDERS:
                - Generate EXACTLY 5 phases
                - Phase 5 must have EXACTLY 5 projects
                - Each resource MUST have a REAL URL (no fake links)
                - Steps must be SPECIFIC TOPICS, not generic actions
                - All content must be specific to: %s | %s
                - Use second person ("you", "your") throughout
                        
                Now generate the roadmap.
                """.formatted(
                dreamJob, specialization,
                dreamJob, specialization, userCategory, currentRole, experienceLevel, industries,
                goalType, projectLevel, learningStyle, weeklyHours,
                skillLevelsStr.toString(),
                dreamJob,
                dreamJob, weeklyHours, dreamJob,
                dreamJob, dreamJob, dreamJob,
                dreamJob, specialization
        );
    }

    /**
     * Generates specialization options for a given dream job.
     * Called before roadmap generation — user picks one specialization.
     * Returns 4-6 options with title, description, and core technologies.
     */
    public SpecializationResponse getSpecializations(String dreamJob) {
        log.info("Fetching specializations for dream job: {}", dreamJob);

        String requestBody = buildSpecializationRequestBody(dreamJob);

        // direct HTTP call — bypass callApi() which uses extractContent()
        // extractContent() validates for analysis/careerGuidance sections
        // specialization response has neither — needs its own extractor
        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Groq API error for specializations: {}", response.code());
                throw new GeminiApiException("Failed to get specializations. Please try again.");
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            String content = extractSpecializationContent(responseBody);
            return parseSpecializationResponse(dreamJob, content);

        } catch (IOException ex) {
            log.error("Groq connection failed for specializations: {}", ex.getMessage());
            throw new GeminiApiException("Failed to connect to AI service. Please try again.");
        }
    }

    /**
     * Builds request body for specialization generation.
     * Low temperature — we want consistent, factual options not creative ones.
     */
    private String buildSpecializationRequestBody(String dreamJob) {
        String systemPrompt = """
            You are a career guidance expert. Return ONLY valid JSON — no explanation, no markdown.
            """;

        String userPrompt = """
        List specializations for this dream job: "%s"
        
        Return ONLY this exact JSON:
        {
          "specializations": [
            {
              "title": "specialization name",
              "description": "one line — main technologies involved",
              "coreTech": ["tech1", "tech2", "tech3", "tech4"]
            }
          ]
        }
        
        Rules:
        - Return exactly 7 specializations
        - All 7 must be variations or sub-fields of "%s" specifically
        - Stay within the same domain — do not go outside "%s" field
        - Example for "Full Stack Developer":
            MERN Stack, MEAN Stack, Java Full Stack, Python Full Stack,
            .NET Full Stack, Flutter Full Stack, Serverless Full Stack
        - Example for "Data Scientist":
            Machine Learning, Deep Learning, NLP, Computer Vision,
            Data Engineering, MLOps, Reinforcement Learning
        - Each must be genuinely distinct from others
        - coreTech: 3 to 5 core technologies per specialization
        - No niche or outdated specializations
        - If dream job has fewer than 7 clear specializations,
          include role-level variations like Junior/Mid/Senior tracks
        """.formatted(dreamJob, dreamJob, dreamJob);

        return """
            {
              "model": "%s",
              "messages": [
                {"role": "system", "content": "%s"},
                {"role": "user", "content": "%s"}
              ],
              "max_tokens": 800,
              "temperature": 0.1,
              "response_format": {"type": "json_object"}
            }
            """.formatted(model, escapeJson(systemPrompt), escapeJson(userPrompt));
    }

    /**
     * Parses Groq specialization response into SpecializationResponse DTO.
     * Throws GeminiApiException if response is malformed.
     */
    private SpecializationResponse parseSpecializationResponse(String dreamJob, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode specs = root.path("specializations");

            List<SpecializationResponse.SpecializationOption> options = new ArrayList<>();

            for (JsonNode spec : specs) {
                List<String> coreTech = new ArrayList<>();
                spec.path("coreTech").forEach(t -> coreTech.add(t.asText()));

                options.add(new SpecializationResponse.SpecializationOption(
                        spec.path("title").asText(),
                        spec.path("description").asText(),
                        coreTech
                ));
            }

            return new SpecializationResponse(dreamJob, options);

        } catch (Exception ex) {
            log.error("Failed to parse specialization response: {}", ex.getMessage());
            throw new GeminiApiException("Failed to get specializations. Please try again.");
        }
    }

    /**
     * Extracts content for specialization response.
     * Does NOT validate for analysis/careerGuidance sections —
     * specialization response has different structure.
     */
    private String extractSpecializationContent(String responseBody) {
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

            // strip markdown fences if present
            content = content
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // validate it's at least valid JSON
            objectMapper.readTree(content);

            log.info("Specialization response extracted successfully");
            return content;

        } catch (GeminiApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to parse specialization response: {}", ex.getMessage());
            throw new GeminiApiException("AI returned unreadable response. Please try again.");
        }
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