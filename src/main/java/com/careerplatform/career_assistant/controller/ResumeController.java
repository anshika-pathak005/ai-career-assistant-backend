package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.response.ResumeAnalysisResponse;
import com.careerplatform.career_assistant.dto.response.ResumeListItemResponse;
import com.careerplatform.career_assistant.service.ResumeProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeProcessingService resumeProcessingService;

    /**
     * POST /api/resume/upload
     * Upload and analyze resume.
     * Checks monthly limit before processing.
     * Returns full analysis + usage info.
     */
    @PostMapping("/upload")
    public ResponseEntity<ResumeAnalysisResponse> uploadResume(
            @RequestParam("file") MultipartFile file) {
        log.info("Resume upload request: {}", file.getOriginalFilename());
        return ResponseEntity.ok(resumeProcessingService.uploadAndAnalyze(file));
    }

    /**
     * GET /api/resume/my-resumes
     * Returns lightweight list of all resumes for current user.
     * Used for history/list view — no full analysis data.
     */
    @GetMapping("/my-resumes")
    public ResponseEntity<List<ResumeListItemResponse>> getMyResumes() {
        log.info("Resume list request received");
        return ResponseEntity.ok(resumeProcessingService.getMyResumes());
    }

    /**
     * GET /api/resume/{id}
     * Returns specific resume with full analysis.
     * User can only access their own resumes.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResumeAnalysisResponse> getResume(@PathVariable Long id) {
        log.info("Resume fetch request for id: {}", id);
        return ResponseEntity.ok(resumeProcessingService.getResumeById(id));
    }

    /**
     * DELETE /api/resume/{id}
     * Deletes resume and file from disk.
     * User can only delete their own resumes.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable Long id) {
        log.info("Resume delete request for id: {}", id);
        resumeProcessingService.deleteResume(id);
        return ResponseEntity.noContent().build();
    }
}