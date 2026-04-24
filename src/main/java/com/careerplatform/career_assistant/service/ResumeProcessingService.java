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
    private final GeminiService geminiService;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Complete resume processing pipeline:
     * save file → extract text → call Gemini → parse response → save to DB → return response
     */
    @Transactional
    public ResumeUploadResponse uploadAndAnalyze(MultipartFile file) {

        // get current logged in user from SecurityContext — set by JwtAuthenticationFilter
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        log.info("Starting resume processing for userId: {}", userId);

        // step 1 — save file to disk
        String filePath = fileStorageService.saveFile(file);

        // step 2 — save resume record to DB
        Resume resume = Resume.builder()
                .user(user)
                .fileName(file.getOriginalFilename())
                .filePath(filePath)
                .fileSize(file.getSize())
                .fileType(file.getContentType())
                .build();
        resume = resumeRepository.save(resume);
        log.info("Resume saved to DB with id: {}", resume.getId());

        // step 3 — extract text from file
        String extractedText = textExtractionService.extractText(filePath);
        System.out.println("Extracted text :" + extractedText);

        // step 4 — send to Gemini for analysis
        String aiResponseJson = geminiService.analyzeResume(extractedText);
        System.out.println("ai response : " + aiResponseJson);

        // step 5 — parse AI response and save analysis
        AnalysisResult analysis = parseAndSaveAnalysis(resume, aiResponseJson);
        System.out.println("analylis result : "+ analysis);

        log.info("Resume analysis complete for resumeId: {}", resume.getId());

        // step 6 — build and return response
        return buildUploadResponse(resume, analysis);
    }

    // get all resumes for current logged in user
    public List<ResumeListResponse> getMyResumes() {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Resume> resumes = resumeRepository.findByUserOrderByUploadTimeDesc(user);

        return resumes.stream().map(resume -> {
            // get analysis scores if they exist
            return analysisResultRepository.findByResume(resume)
                    .map(analysis -> new ResumeListResponse(
                            resume.getId(),
                            resume.getFileName(),
                            resume.getFileSize(),
                            resume.getUploadTime(),
                            analysis.getOverallScore(),
                            analysis.getAtsScore(),
                            analysis.getExperienceLevel()
                    ))
                    .orElse(new ResumeListResponse(
                            resume.getId(),
                            resume.getFileName(),
                            resume.getFileSize(),
                            resume.getUploadTime(),
                            null, null, null
                    ));
        }).toList();
    }

    // get specific resume with analysis
    public ResumeUploadResponse getResumeById(Long resumeId) {
        Long userId = getCurrentUserId();

        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        // make sure user can only access their own resumes
        if (!resume.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Resume not found");
        }

        AnalysisResult analysis = analysisResultRepository.findByResume(resume)
                .orElseThrow(() -> new ResourceNotFoundException("Analysis not found for this resume"));

        return buildUploadResponse(resume, analysis);
    }

    // delete resume + file from disk
    @Transactional
    public void deleteResume(Long resumeId) {
        Long userId = getCurrentUserId();

        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));

        if (!resume.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Resume not found");
        }

        // delete file from disk
        fileStorageService.deleteFile(resume.getFilePath());

        // delete from DB — cascades to analysis
        resumeRepository.delete(resume);
        log.info("Resume deleted: {}", resumeId);
    }

    // parses Gemini JSON response and saves AnalysisResult to DB
    private AnalysisResult parseAndSaveAnalysis(Resume resume, String aiJson) {
        try {
            JsonNode node = objectMapper.readTree(aiJson);

            AnalysisResult analysis = AnalysisResult.builder()
                    .resume(resume)
                    .fullName(getTextOrNull(node, "fullName"))
                    .email(getTextOrNull(node, "email"))
                    .phone(getTextOrNull(node, "phone"))
                    .overallScore(getIntOrNull(node, "overallScore"))
                    .atsScore(getIntOrNull(node, "atsScore"))
                    .summary(getTextOrNull(node, "summary"))
                    .experienceLevel(getTextOrNull(node, "experienceLevel"))
                    .totalExperienceYears(getDoubleOrNull(node, "experienceYears"))
                    .currentRole(getTextOrNull(node, "currentRole"))
                    .technicalSkills(node.path("technicalSkills").toString())
                    .softSkills(node.path("softSkills").toString())
                    .missingSkills(node.path("missingSkills").toString())
                    .missingKeywords(node.path("missingKeywords").toString())
                    .strengths(node.path("strengths").toString())
                    .improvements(node.path("improvements").toString())
                    .rawAiResponse(aiJson)
                    .build();

            return analysisResultRepository.save(analysis);

        } catch (Exception ex) {
            log.error("Failed to parse AI response: {}", ex.getMessage());
            throw new RuntimeException("Failed to parse AI analysis response");
        }
    }

    // builds the final API response from resume + analysis entities
    private ResumeUploadResponse buildUploadResponse(Resume resume, AnalysisResult analysis) {
        try {
            return new ResumeUploadResponse(
                    resume.getId(),
                    resume.getFileName(),
                    "Resume uploaded and analyzed successfully",
                    new ResumeUploadResponse.AnalysisDto(
                            analysis.getFullName(),
                            analysis.getEmail(),
                            analysis.getPhone(),
                            analysis.getOverallScore(),
                            analysis.getAtsScore(),
                            analysis.getSummary(),
                            analysis.getExperienceLevel(),
                            analysis.getTotalExperienceYears(),
                            analysis.getCurrentRole(),
                            parseJsonArray(analysis.getStrengths()),
                            parseJsonArray(analysis.getImprovements()),
                            parseJsonArray(analysis.getMissingSkills()),
                            parseJsonArray(analysis.getMissingKeywords()),
                            parseJsonArray(analysis.getTechnicalSkills())
                    )
            );
        } catch (Exception ex) {
            log.error("Failed to build response: {}", ex.getMessage());
            throw new RuntimeException("Failed to build response");
        }
    }

    // parses JSON array string back to List<String>
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

    // extracts current userId from Spring SecurityContext
    // set by JwtAuthenticationFilter on every authenticated request
    private Long getCurrentUserId() {
        String userId = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName(); // returns the username we set — which is userId
        return Long.parseLong(userId);
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