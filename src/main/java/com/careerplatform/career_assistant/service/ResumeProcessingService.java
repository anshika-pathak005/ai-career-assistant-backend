package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.response.ResumeListResponse;
import com.careerplatform.career_assistant.dto.response.ResumeUploadResponse;
import com.careerplatform.career_assistant.entity.AnalysisResult;
import com.careerplatform.career_assistant.entity.Resume;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.AnalysisResultRepository;
import com.careerplatform.career_assistant.repository.ResumeRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.util.ResumeTextFormatter;
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
    private final DeepSeekService deepSeekService;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final UserRepository userRepository;
    private final ResumeTextFormatter resumeTextFormatter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Complete pipeline:
     * save file → extract text → format text → call AI with user profile
     * → parse response → save to DB → return response
     */
    @Transactional
    public ResumeUploadResponse uploadAndAnalyze(MultipartFile file) {

        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        log.info("Starting resume processing for userId: {}", userId);

        // step 1 — save file
        String filePath = fileStorageService.saveFile(file);

        // step 2 — save resume to DB
        Resume resume = Resume.builder()
                .user(user)
                .fileName(file.getOriginalFilename())
                .filePath(filePath)
                .fileSize(file.getSize())
                .fileType(file.getContentType())
                .build();
        resume = resumeRepository.save(resume);

        // step 3 — extract and format text
        String rawText = textExtractionService.extractText(filePath);
        System.out.println("Raw extracted text:\n" + rawText); // debug log to see raw text before formatting

        String cleanedText = resumeTextFormatter.format(rawText);
        System.out.println("Cleaned and formatted text:\n" + cleanedText); // debug log to see cleaned text sent to AI

        log.info("Text extracted and formatted for resumeId: {}", resume.getId());

        // step 4 — send resume text + user profile to AI
        String aiResponseJson = deepSeekService.analyzeResume(cleanedText, user);
        System.out.println("Raw AI response JSON:\n" + aiResponseJson); // debug log to see raw AI response

        // step 5 — parse and save analysis
        AnalysisResult analysis = parseAndSaveAnalysis(resume, aiResponseJson);
        System.out.println("Parsed analysis result:\n" + analysis); // debug log to see parsed analysis result

        log.info("Resume analysis complete for resumeId: {}", resume.getId());
        return buildUploadResponse(resume, analysis);
    }

    public List<ResumeListResponse> getMyResumes() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return resumeRepository.findByUserOrderByUploadTimeDesc(user).stream()
                .map(resume -> analysisResultRepository.findByResume(resume)
                        .map(a -> new ResumeListResponse(
                                resume.getId(), resume.getFileName(),
                                resume.getFileSize(), resume.getUploadTime(),
                                a.getOverallScore(), a.getAtsScore(), a.getExperienceLevel()))
                        .orElse(new ResumeListResponse(
                                resume.getId(), resume.getFileName(),
                                resume.getFileSize(), resume.getUploadTime(),
                                null, null, null)))
                .toList();
    }

    public ResumeUploadResponse getResumeById(Long resumeId) {
        Long userId = getCurrentUserId();
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        if (!resume.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Resume not found");
        }

        AnalysisResult analysis = analysisResultRepository.findByResume(resume)
                .orElseThrow(() -> new ResourceNotFoundException("Analysis not found"));

        return buildUploadResponse(resume, analysis);
    }

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

    // parses AI JSON response — reads both analysis and careerGuidance sections
    private AnalysisResult parseAndSaveAnalysis(Resume resume, String aiJson) {
        try {
            JsonNode root = objectMapper.readTree(aiJson);
            JsonNode analysis = root.path("analysis");
            JsonNode guidance = root.path("careerGuidance");

            AnalysisResult result = AnalysisResult.builder()
                    .resume(resume)
                    // analysis fields
                    .fullName(getTextOrNull(analysis, "fullName"))
                    .email(getTextOrNull(analysis, "email"))
                    .phone(getTextOrNull(analysis, "phone"))
                    .overallScore(getIntOrNull(analysis, "overallScore"))
                    .atsScore(getIntOrNull(analysis, "atsScore"))
                    .summary(getTextOrNull(analysis, "summary"))
                    .experienceLevel(getTextOrNull(analysis, "experienceLevel"))
                    .totalExperienceYears(getDoubleOrNull(analysis, "experienceYears"))
                    .currentRole(getTextOrNull(analysis, "currentRole"))
                    .technicalSkills(analysis.path("technicalSkills").toString())
                    .softSkills(analysis.path("softSkills").toString())
                    .strengths(analysis.path("strengths").toString())
                    .improvements(analysis.path("improvements").toString())
                    .missingSkills(analysis.path("missingSkills").toString())
                    .missingKeywords(analysis.path("missingKeywords").toString())
                    // career guidance fields
                    .recommendedRoles(guidance.path("recommendedRoles").toString())
                    .learningRoadmap(guidance.path("learningRoadmap").toString())
                    .projectSuggestions(guidance.path("projectSuggestions").toString())
                    .careerAdvice(getTextOrNull(guidance, "careerAdvice"))
                    .rawAiResponse(aiJson)
                    .build();

            return analysisResultRepository.save(result);

        } catch (Exception ex) {
            log.error("Failed to parse AI response: {}", ex.getMessage());
            throw new RuntimeException("Failed to save analysis results");
        }
    }

    private ResumeUploadResponse buildUploadResponse(Resume resume, AnalysisResult a) {
        return new ResumeUploadResponse(
                resume.getId(),
                resume.getFileName(),
                "Resume uploaded and analyzed successfully",
                new ResumeUploadResponse.AnalysisDto(
                        a.getFullName(), a.getEmail(), a.getPhone(),
                        a.getOverallScore(), a.getAtsScore(), a.getSummary(),
                        a.getExperienceLevel(), a.getTotalExperienceYears(), a.getCurrentRole(),
                        parseJsonArray(a.getTechnicalSkills()),
                        parseJsonArray(a.getSoftSkills()),
                        parseJsonArray(a.getStrengths()),
                        parseJsonArray(a.getImprovements()),
                        parseJsonArray(a.getMissingSkills()),
                        parseJsonArray(a.getMissingKeywords())
                ),
                new ResumeUploadResponse.CareerGuidanceDto(
                        parseJsonArray(a.getRecommendedRoles()),
                        parseJsonArray(a.getLearningRoadmap()),
                        parseJsonArray(a.getProjectSuggestions()),
                        a.getCareerAdvice()
                )
        );
    }

    private List<String> parseJsonArray(String jsonArray) {
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

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }

    private String getTextOrNull(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull()
                ? node.path(field).asText() : null;
    }

    private Integer getIntOrNull(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull()
                ? node.path(field).asInt() : null;
    }

    private Double getDoubleOrNull(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull()
                ? node.path(field).asDouble() : null;
    }
}