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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * Main resume processing pipeline.
     * Checks limit → saves file → extracts text → calls AI → saves analysis → returns response.
     */
    @Transactional
    public ResumeAnalysisResponse uploadAndAnalyze(MultipartFile file) {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // check monthly limit before doing anything
        generationLimitService.checkAndIncrement(user, LimitType.RESUME);

        log.info("Resume processing started for userId: {}", userId);

        // save file to disk
        String filePath = fileStorageService.saveFile(file);

        // save resume record to DB
        Resume resume = Resume.builder()
                .user(user)
                .fileName(file.getOriginalFilename())
                .filePath(filePath)
                .fileSize(file.getSize())
                .fileType(file.getContentType())
                .build();
        resume = resumeRepository.save(resume);

        // extract and clean text
        String rawText = textExtractionService.extractText(filePath);

        // call AI with resume text + user profile
        String aiResponseJson = resumeGroqService.analyzeResume(rawText, user);

        // parse and save analysis
        AnalysisResult analysis = parseAndSaveAnalysis(resume, user, aiResponseJson);

        // refresh user — limit counter was updated
        user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        log.info("Resume analysis complete for resumeId: {}", resume.getId());
        return buildResponse(resume, analysis,
                generationLimitService.buildResumeLimitResponse(user));
    }

    /**
     * Returns all resumes for current user — lightweight list for history.
     */
    public List<ResumeListItemResponse> getMyResumes() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return resumeRepository.findByUserOrderByUploadTimeDesc(user).stream()
                .map(resume -> analysisResultRepository.findByResume(resume)
                        .map(a -> new ResumeListItemResponse(
                                resume.getId(), a.getId(),
                                resume.getFileName(), resume.getUploadTime(),
                                a.getOverallScore(), a.getAtsScore(),
                                a.getInterviewReadiness(), a.getExperienceLevel()))
                        .orElse(new ResumeListItemResponse(
                                resume.getId(), null,
                                resume.getFileName(), resume.getUploadTime(),
                                null, null, null, null)))
                .toList();
    }

    /**
     * Returns specific resume with full analysis by resumeId.
     * User can only access their own resumes.
     */
    public ResumeAnalysisResponse getResumeById(Long resumeId) {
        Long userId = getCurrentUserId();

        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        if (!resume.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Resume not found");
        }

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
    public void deleteResume(Long resumeId) {
        Long userId = getCurrentUserId();

        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        if (!resume.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Resume not found");
        }

        fileStorageService.deleteFile(resume.getFilePath());
        resumeRepository.delete(resume);
        log.info("Resume deleted: {}", resumeId);
    }

    // ── private helpers ───────────────────────────────────────────

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
                    .overallScore(scores.path("overallScore").asInt())
                    .atsScore(scores.path("atsScore").asInt())
                    .technicalSkillsScore(scores.path("technicalSkillsScore").asInt())
                    .projectQualityScore(scores.path("projectQualityScore").asInt())
                    .formattingScore(scores.path("formattingScore").asInt())
                    .technicalSkills(root.path("technicalSkills").toString())
                    .strengths(root.path("strengths").toString())
                    .weaknesses(root.path("weaknesses").toString())
                    .missingSkills(root.path("missingSkills").toString())
                    .missingKeywords(root.path("missingKeywords").toString())
                    .improvementSuggestions(root.path("improvementSuggestions").toString())
                    .projectSuggestions(root.path("projectSuggestions").toString())
                    .recommendedLearning(root.path("recommendedLearning").toString())
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
                        parseArray(a.getTechnicalSkills()),
                        parseArray(a.getStrengths()),
                        parseArray(a.getWeaknesses()),
                        parseArray(a.getMissingSkills()),
                        parseArray(a.getMissingKeywords()),
                        parseArray(a.getImprovementSuggestions()),
                        parseArray(a.getProjectSuggestions()),
                        parseArray(a.getRecommendedLearning())
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

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }
}