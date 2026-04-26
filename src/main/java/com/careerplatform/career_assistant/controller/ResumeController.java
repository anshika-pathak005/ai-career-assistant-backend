package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.ProfileAnalysisRequest;
import com.careerplatform.career_assistant.dto.response.ResumeListResponse;
import com.careerplatform.career_assistant.dto.response.ResumeUploadResponse;
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

    // POST /api/resume/upload
    // multipart/form-data — file field name must be "file"
    // requires JWT token in Authorization header
    @PostMapping("/upload")
    public ResponseEntity<ResumeUploadResponse> uploadResume(
            @RequestParam("file") MultipartFile file) {
        log.info("Resume upload request received: {}", file.getOriginalFilename());
        return ResponseEntity.ok(resumeProcessingService.uploadAndAnalyze(file));
    }

    // POST /api/resume/analyze-from-profile
    // for users with no resume — updates profile and triggers AI analysis
    @PostMapping("/analyze-from-profile")
    public ResponseEntity<ResumeUploadResponse> analyzeFromProfile(
            @RequestBody ProfileAnalysisRequest request) {
        log.info("Profile-based analysis request received");
        return ResponseEntity.ok(resumeProcessingService.analyzeFromProfile(request));
    }

    // GET /api/resume/my-resumes
    // returns all resumes for currently logged in user
    @GetMapping("/my-resumes")
    public ResponseEntity<List<ResumeListResponse>> getMyResumes() {
        log.info("Fetching resumes for current user");
        return ResponseEntity.ok(resumeProcessingService.getMyResumes());
    }

    // GET /api/resume/{id}
    // returns specific resume with full analysis
    @GetMapping("/{id}")
    public ResponseEntity<ResumeUploadResponse> getResume(@PathVariable Long id) {
        log.info("Fetching resume with id: {}", id);
        return ResponseEntity.ok(resumeProcessingService.getResumeById(id));
    }

    // DELETE /api/resume/{id}
    // deletes resume from DB and file from disk
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable Long id) {
        log.info("Deleting resume with id: {}", id);
        resumeProcessingService.deleteResume(id);
        return ResponseEntity.noContent().build(); // 204
    }
}