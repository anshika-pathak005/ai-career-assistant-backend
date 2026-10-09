package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.response.*;
import com.careerplatform.career_assistant.entity.*;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.*;
import com.careerplatform.career_assistant.service.GenerationLimitService.LimitType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProcessingService {

    private final FileStorageService fileStorageService;
    private final TextExtractionService textExtractionService;
    private final ResumeGroqService resumeGroqService;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final UserRepository userRepository;
    private final GenerationLimitService generationLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionTemplate transactionTemplate;

    /**
     * Main resume processing pipeline.
     * Checks limit → saves file → extracts text → calls AI → saves analysis → returns response.
     */
    /**
     * NOT @Transactional — deliberately.
     *
     * A short transaction consumes quota, then file I/O, Tika extraction and the Groq
     * call run with no connection held, then a second short transaction persists the
     * results.
     *
     * Wrapping the whole method would hold a JDBC connection for the entire AI call.
     * With HikariCP's default pool of 10, about ten concurrent uploads would take every
     * connection and stall every other request in the app.
     *
     * TransactionTemplate rather than private @Transactional methods, because Spring
     * proxies the bean — a self-invoked method bypasses the proxy, so its @Transactional
     * would be silently ignored.
     *
     * The HTTP thread is still parked for the duration — making this asynchronous is
     * what fixes that.
     */
    public ResumeAnalysisResponse uploadAndAnalyze(Long userId, MultipartFile file) {

        // ── TX 1: short, DB only ──────────────────────────────────────────────
        // Consume quota up front so parallel uploads cannot all pass the check.
        User user = transactionTemplate.execute(status -> {
            // Quota first: consume() runs a bulk UPDATE with clearAutomatically, so any
            // entity loaded before it would come back detached.
            generationLimitService.consume(userId, LimitType.RESUME);

            return userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        });

        log.info("Resume processing started for userId: {}", userId);

        // ── No transaction: file I/O and the slow AI call ─────────────────────
        String filePath = null;
        try {
            filePath = fileStorageService.saveFile(file);

            String rawText = textExtractionService.extractText(filePath);
            String aiResponseJson = resumeGroqService.analyzeResume(rawText, user);

            // ── TX 2: short, DB only ──────────────────────────────────────────
            final String savedPath = filePath;
            return transactionTemplate.execute(status -> {
                User fresh = userRepository.findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found"));

                Resume resume = resumeRepository.save(Resume.builder()
                        .user(fresh)
                        .fileName(file.getOriginalFilename())
                        .filePath(savedPath)
                        .fileSize(file.getSize())
                        .fileType(file.getContentType())
                        .build());

                AnalysisResult analysis = parseAndSaveAnalysis(resume, fresh, aiResponseJson);

                log.info("Resume analysis complete for resumeId: {}", resume.getId());
                return buildResponse(resume, analysis,
                        generationLimitService.buildResumeLimitResponse(fresh));
            });

        } catch (RuntimeException ex) {
            // Refund the quota — the user should not be charged for a failed analysis.
            generationLimitService.release(userId, LimitType.RESUME);

            // Remove the file we wrote. The Resume row is only inserted in TX 2, so a
            // failure before that point would otherwise leave an unreferenced file on
            // disk forever — Files.copy is not covered by any transaction.
            if (filePath != null) {
                fileStorageService.deleteFile(filePath);
            }
            throw ex;
        }
    }

    /**
     * Returns all resumes for current user — lightweight list for history.
     *
     * 5.5: this was the N+1. It loaded the resume list, then called
     * analysisResultRepository.findByResume() once per row inside the stream — 20
     * resumes meant 1 + 20 = 21 round trips. It is now a single LEFT JOIN projection
     * that builds the DTOs in the database, so the query count no longer depends on how
     * many resumes the user has.
     *
     * The User lookup is gone too. It existed only to pass an entity to
     * findByUserOrderByUploadTimeDesc; the id from the JWT is enough, and a caller who
     * got past JwtAuthenticationFilter necessarily has a row. That removes one more
     * query from a read-only path.
     *
     * Still unbounded — pagination is noted as deferred in PHASE5-CHANGES.md because it
     * changes the response shape and needs a frontend change to land with it.
     */
    @Transactional(readOnly = true)
    public List<ResumeListItemResponse> getMyResumes(Long userId) {
        return resumeRepository.findListForUser(userId);
    }

    /**
     * Returns specific resume with full analysis by resumeId.
     * User can only access their own resumes.
     */
    public ResumeAnalysisResponse getResumeById(Long userId, Long resumeId) {

        // SECURITY: ownership enforced in the query, not by a follow-up check.
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        AnalysisResult analysis = analysisResultRepository.findByResume(resume)
                .orElseThrow(() -> new ResourceNotFoundException("Analysis not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return buildResponse(resume, analysis,
                generationLimitService.buildResumeLimitResponse(user));
    }

    /**
     * Deletes resume and its file from disk.
     */
    @Transactional
    public void deleteResume(Long userId, Long resumeId) {

        // SECURITY: ownership enforced in the query, not by a follow-up check.
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        String filePath = resume.getFilePath();
        resumeRepository.delete(resume);

        // Delete the file only AFTER the transaction commits. Doing it first meant
        // a rollback left the row pointing at a file that no longer existed.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        fileStorageService.deleteFile(filePath);
                    }
                });
        log.info("Resume deleted: {}", resumeId);
    }

    // ── private helpers ───────────────────────────────────────────

    /**
     * Reads a score from the AI response and forces it into the range 0-100.
     *
     * The model's output is untrusted for the same reason the resume is: text in the
     * resume can influence it, and whatever comes back would otherwise be written
     * straight to the database.
     *
     * Clamping does not prevent injection — it bounds the damage to something the UI can
     * render and a human can sanity check.
     */
    private int score(com.fasterxml.jackson.databind.JsonNode scores, String field) {
        int raw = scores.path(field).asInt(0);
        if (raw < 0 || raw > 100) {
            log.warn("AI returned out-of-range {}: {} - clamping", field, raw);
        }
        return Math.max(0, Math.min(100, raw));
    }

    private AnalysisResult parseAndSaveAnalysis(Resume resume, User user, String aiJson) {
        try {
            JsonNode root = objectMapper.readTree(aiJson);
            JsonNode scores = root.path("scores");

            AnalysisResult result = AnalysisResult.builder()
                    .resume(resume)
                    .user(user)
                    .fullName(text(root, "fullName"))
                    .email(text(root, "email"))
                    .phone(text(root, "phone"))
                    .currentRole(text(root, "currentRole"))
                    .experienceLevel(text(root, "experienceLevel"))
                    .experienceYears(root.path("experienceYears").asDouble())
                    .interviewReadiness(text(root, "interviewReadiness"))
                    .dreamJobMatchSummary(text(root, "dreamJobMatchSummary"))
                    .summary(text(root, "summary"))
                    .overallScore(score(scores, "overallScore"))
                    .atsScore(score(scores, "atsScore"))
                    .technicalSkillsScore(score(scores, "technicalSkillsScore"))
                    .projectQualityScore(score(scores, "projectQualityScore"))
                    .formattingScore(score(scores, "formattingScore"))
                    .technicalSkills(root.path("technicalSkills").toString())
                    .strengths(root.path("strengths").toString())
                    .weaknesses(root.path("weaknesses").toString())
                    .missingSkills(root.path("missingSkills").toString())
                    .missingKeywords(root.path("missingKeywords").toString())
                    .improvementSuggestions(root.path("improvementSuggestions").toString())
                    .projectSuggestions(root.path("projectSuggestions").toString())
                    .recommendedLearning(root.path("recommendedLearning").toString())
                    .resumeVerdict(text(root, "resumeVerdict"))
                    .recruiterPerspective(text(root, "recruiterPerspective"))
                    .prioritySkills(root.path("prioritySkills").toString())
                    .recommendedRoles(root.path("recommendedRoles").toString())
                    .recommendedCertifications(root.path("recommendedCertifications").toString())
                    .atsSuggestions(root.path("atsSuggestions").toString())
                    .sectionAnalysis(root.path("sectionAnalysis").toString())
                    .rawAiResponse(aiJson)
                    .build();

            return analysisResultRepository.save(result);

        } catch (Exception ex) {
            log.error("Failed to parse analysis response: {}", ex.getMessage());
            throw new RuntimeException("Failed to save analysis results");
        }
    }

    private ResumeAnalysisResponse buildResponse(Resume resume,
                                                 AnalysisResult a,
                                                 GenerationLimitResponse usageInfo) {
        return new ResumeAnalysisResponse(
                resume.getId(),
                a.getId(),
                resume.getFileName(),
                resume.getUploadTime(),
                "Resume analyzed successfully",
                new ResumeAnalysisResponse.ScoresDto(
                        a.getOverallScore(), a.getAtsScore(),
                        a.getTechnicalSkillsScore(), a.getProjectQualityScore(),
                        a.getFormattingScore()
                ),
                new ResumeAnalysisResponse.BasicInfoDto(
                        a.getFullName(), a.getEmail(), a.getPhone(),
                        a.getCurrentRole(), a.getExperienceLevel(),
                        a.getExperienceYears(), a.getInterviewReadiness(),
                        a.getDreamJobMatchSummary()
                ),
                new ResumeAnalysisResponse.AnalysisDto(
                        a.getSummary(),
                        a.getResumeVerdict(),           // new
                        a.getRecruiterPerspective(),    // new
                        a.getDreamJobMatchSummary(),
                        a.getInterviewReadiness(),
                        parseArray(a.getTechnicalSkills()),
                        parseArray(a.getStrengths()),
                        parseArray(a.getWeaknesses()),
                        parseArray(a.getMissingSkills()),
                        parseArray(a.getPrioritySkills()),          // new
                        parseArray(a.getMissingKeywords()),
                        parseArray(a.getAtsSuggestions()),          // new
                        parseArray(a.getImprovementSuggestions()),
                        parseArray(a.getProjectSuggestions()),
                        parseArray(a.getRecommendedLearning()),
                        parseArray(a.getRecommendedRoles()),        // new
                        parseArray(a.getRecommendedCertifications()),// new
                        parseSectionAnalysis(a.getSectionAnalysis()) // new
                ),
                usageInfo
        );
    }

    private List<String> parseArray(String jsonArray) {
        if (jsonArray == null) return new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(jsonArray);
            List<String> list = new ArrayList<>();
            node.forEach(item -> list.add(item.asText()));
            return list;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String text(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull()
                ? node.path(field).asText() : null;
    }

    /**
     * Parses sectionAnalysis JSON object into SectionAnalysisDto.
     * sectionAnalysis is stored as JSON string in DB.
     */
    private ResumeAnalysisResponse.SectionAnalysisDto parseSectionAnalysis(String json) {
        if (json == null) return new ResumeAnalysisResponse.SectionAnalysisDto();
        try {
            JsonNode node = objectMapper.readTree(json);
            return new ResumeAnalysisResponse.SectionAnalysisDto(
                    node.path("education").asText(""),
                    node.path("skills").asText(""),
                    node.path("projects").asText(""),
                    node.path("experience").asText(""),
                    node.path("certifications").asText("")
            );
        } catch (Exception ex) {
            return new ResumeAnalysisResponse.SectionAnalysisDto();
        }
    }
}