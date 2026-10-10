package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.response.ResumeAnalysisResponse;
import com.careerplatform.career_assistant.dto.response.ResumeListItemResponse;
import com.careerplatform.career_assistant.ratelimit.RateLimit;
import com.careerplatform.career_assistant.ratelimit.RateLimitTier;
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.ResumeProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeProcessingService resumeProcessingService;

    /**
     * POST /api/v1/resume/upload
     * Uploads and analyses a resume. Consumes monthly quota before processing.
     */
    // Tika extraction plus a Groq call at ~2500 max tokens.
    @RateLimit(tier = RateLimitTier.AI, cost = 3)
    @PostMapping("/upload")
    public ResponseEntity<ResumeAnalysisResponse> uploadResume(
            @CurrentUserId Long userId,
            @RequestParam("file") MultipartFile file) {

        /*
         * 6.7: this logged file.getOriginalFilename(). Resume filenames are routinely
         * "Firstname_Lastname_Resume.pdf", which put the user's real name into the
         * application log on every upload - and therefore into the log aggregator, which
         * is a lower-trust store than the database. Size and declared type are what an
         * operator debugging an upload actually needs.
         */
        log.info("Resume upload for userId: {} ({} bytes, type {})",
                userId, file.getSize(), file.getContentType());

        return ResponseEntity.ok(resumeProcessingService.uploadAndAnalyze(userId, file));
    }

    /**
     * GET /api/v1/resume/my-resumes
     * Lightweight list for the history view — no full analysis data.
     */
    @GetMapping("/my-resumes")
    public ResponseEntity<List<ResumeListItemResponse>> getMyResumes(@CurrentUserId Long userId) {
        log.info("Resume list request for userId: {}", userId);
        return ResponseEntity.ok(resumeProcessingService.getMyResumes(userId));
    }

    /**
     * GET /api/v1/resume/{id}
     * Full analysis for one resume. Ownership is enforced in the query.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResumeAnalysisResponse> getResume(
            @CurrentUserId Long userId,
            @PathVariable Long id) {
        log.info("Resume fetch request for id: {} by userId: {}", id, userId);
        return ResponseEntity.ok(resumeProcessingService.getResumeById(userId, id));
    }

    /**
     * GET /api/v1/resume/{id}/download
     * Downloads the resume file from Supabase Storage.
     * Ownership is enforced in the query, file served with appropriate headers.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadResume(
            @CurrentUserId Long userId,
            @PathVariable Long id) {
        log.info("Resume download request for id: {} by userId: {}", id, userId);

        ResumeProcessingService.ResumeDownloadResponse downloadResponse =
                resumeProcessingService.downloadResume(userId, id);

        // Set content disposition for download
        ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                .filename(downloadResponse.fileName(), StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(contentDisposition);
        headers.setContentType(MediaType.parseMediaType(downloadResponse.contentType()));
        headers.setContentLength(downloadResponse.content().length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(downloadResponse.content());
    }

    /**
     * DELETE /api/v1/resume/{id}
     * Deletes the row and the file from Supabase Storage. Ownership is enforced in the query.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(
            @CurrentUserId Long userId,
            @PathVariable Long id) {
        log.info("Resume delete request for id: {} by userId: {}", id, userId);
        resumeProcessingService.deleteResume(userId, id);
        return ResponseEntity.noContent().build();
    }
}