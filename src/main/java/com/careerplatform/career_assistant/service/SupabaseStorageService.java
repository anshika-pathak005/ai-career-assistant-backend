package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.config.properties.SupabaseStorageProperties;
import com.careerplatform.career_assistant.exception.FileUploadException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service handling Supabase Storage operations for resume files.
 *
 * Replaces local file storage (`uploads/resumes`) with cloud storage in Supabase's
 * private bucket `careerPilot_resumes`. Maintains all existing security validations:
 * - HTTP Content-Type header validation
 * - Apache Tika magic-bytes validation (prevents file extension spoofing)
 * - File size limits (5MB)
 * - Path sanitization and user isolation
 *
 * Supabase Storage REST API details:
 * - Base URL: https://fkthyogkozhpwqoygqth.supabase.co
 * - Bucket: careerPilot_resumes (private)
 * - Authentication: Authorization: Bearer <secretKey>, apikey: <secretKey>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupabaseStorageService {

    private final SupabaseStorageProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * HTTP client configured for Supabase Storage API.
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * Tika instance for magic-bytes validation.
     */
    private final Tika tika = new Tika();

    /**
     * Types accepted from the HTTP Content-Type header.
     * The client chooses this header and can lie about it, so treat it as a convenience
     * filter — the magic-bytes check below is the real control.
     */
    private static final List<String> ALLOWED_DECLARED_TYPES = Arrays.asList(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    /**
     * Types accepted from Tika's magic-bytes detection.
     *
     * Includes the generic OOXML and ZIP container types, because a DOCX is a ZIP and
     * what Tika reports depends on how far into the container it reads.
     */
    private static final List<String> ALLOWED_DETECTED_TYPES = Arrays.asList(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-tika-ooxml",
            "application/x-tika-msoffice",
            "application/zip"
    );

    /**
     * Uploads a resume file to Supabase Storage.
     *
     * Validates the file (size, type, magic-bytes), generates a user-isolated path,
     * and uploads to Supabase.
     *
     * @param userId The user ID for path isolation
     * @param file   The uploaded resume file
     * @return The Supabase storage object path (e.g., "resumes/123/uuid_filename.pdf")
     * @throws FileUploadException if validation fails or upload fails
     */
    public String uploadResume(Long userId, MultipartFile file) {
        // Validate file not empty
        if (file.isEmpty()) {
            throw new FileUploadException("File is empty");
        }

        // Validate HTTP header file type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_DECLARED_TYPES.contains(contentType)) {
            throw new FileUploadException("Invalid file type. Only PDF and DOCX allowed");
        }

        // Validate file size (5MB = 5 * 1024 * 1024)
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new FileUploadException("File size exceeds the 5MB limit");
        }

        // Magic-bytes validation using Apache Tika
        try (InputStream inputStream = file.getInputStream()) {
            String detectedType = tika.detect(inputStream);
            log.debug("Tika detected magic-bytes type: {}", detectedType);

            if (!ALLOWED_DETECTED_TYPES.contains(detectedType)) {
                log.warn("Magic bytes mismatch! Declared: {}, Detected: {}", contentType, detectedType);
                throw new FileUploadException("File content does not match allowed formats (PDF/DOCX)");
            }
        } catch (IOException ex) {
            log.error("Failed to inspect file magic bytes: {}", ex.getMessage());
            throw new FileUploadException("Could not verify file content type.");
        }

        // Generate safe object path
        String rawFileName = file.getOriginalFilename();
        String safeFileName = sanitizeFilename(rawFileName);
        String uuid = UUID.randomUUID().toString();
        String objectPath = String.format("resumes/%d/%s_%s", userId, uuid, safeFileName);

        // Upload to Supabase
        try (InputStream fileStream = file.getInputStream()) {
            String url = String.format("%s/storage/v1/object/%s/%s",
                    properties.normalizedUrl(),
                    properties.bucket(),
                    URLEncoder.encode(objectPath, StandardCharsets.UTF_8));

            RequestBody body = RequestBody.create(
                    fileStream.readAllBytes(),
                    MediaType.parse(contentType)
            );

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Authorization", "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey())
                    .header("Content-Type", contentType)
                    .header("x-upsert", "false")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Supabase upload failed: {} - {}", response.code(), errorBody);
                    throw new FileUploadException("Failed to upload file to storage. Please try again.");
                }

                log.info("Resume stored in Supabase: {} ({} bytes)", objectPath, file.getSize());
                return objectPath;
            }
        } catch (IOException ex) {
            log.error("Failed to upload file to Supabase: {}", ex.getMessage());
            throw new FileUploadException("Could not save file. Please try again.");
        }
    }

    /**
     * Downloads a resume file from Supabase Storage.
     *
     * @param objectPath The Supabase object path
     * @return The file bytes
     * @throws FileUploadException if download fails
     */
    public byte[] downloadResume(String objectPath) {
        try {
            String url = String.format("%s/storage/v1/object/%s/%s",
                    properties.normalizedUrl(),
                    properties.bucket(),
                    URLEncoder.encode(objectPath, StandardCharsets.UTF_8));

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 404) {
                        log.warn("File not found in Supabase: {}", objectPath);
                        throw new FileUploadException("File not found in storage");
                    }
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Supabase download failed: {} - {}", response.code(), errorBody);
                    throw new FileUploadException("Failed to download file from storage");
                }

                if (response.body() == null) {
                    throw new FileUploadException("Empty response from storage");
                }

                return response.body().bytes();
            }
        } catch (IOException ex) {
            log.error("Failed to download file from Supabase: {}", ex.getMessage());
            throw new FileUploadException("Could not retrieve file from storage.");
        }
    }

    /**
     * Generates a signed URL for temporary access to a resume file.
     *
     * @param objectPath The Supabase object path
     * @param expiresInSeconds How long the signed URL should be valid
     * @return The signed URL
     * @throws FileUploadException if signed URL generation fails
     */
    public String generateSignedUrl(String objectPath, int expiresInSeconds) {
        try {
            String url = String.format("%s/storage/v1/object/sign/%s/%s",
                    properties.normalizedUrl(),
                    properties.bucket(),
                    URLEncoder.encode(objectPath, StandardCharsets.UTF_8));

            String requestBodyJson = objectMapper.writeValueAsString(
                    new SignedUrlRequest(expiresInSeconds)
            );

            RequestBody body = RequestBody.create(
                    requestBodyJson,
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Authorization", "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey())
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Supabase signed URL failed: {} - {}", response.code(), errorBody);
                    throw new FileUploadException("Failed to generate download link");
                }

                if (response.body() == null) {
                    throw new FileUploadException("Empty response from storage");
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                String signedUrl = json.path("signedURL").asText();
                if (signedUrl.isBlank()) {
                    throw new FileUploadException("Invalid signed URL response");
                }

                // The signed URL is relative, make it absolute
                return properties.normalizedUrl() + signedUrl;
            }
        } catch (IOException ex) {
            log.error("Failed to generate signed URL: {}", ex.getMessage());
            throw new FileUploadException("Could not generate download link.");
        }
    }

    /**
     * Deletes a resume file from Supabase Storage.
     *
     * Idempotent: if the file doesn't exist, logs and returns without error.
     *
     * @param objectPath The Supabase object path
     */
    public void deleteResume(String objectPath) {
        try {
            String url = String.format("%s/storage/v1/object/%s/%s",
                    properties.normalizedUrl(),
                    properties.bucket(),
                    URLEncoder.encode(objectPath, StandardCharsets.UTF_8));

            Request request = new Request.Builder()
                    .url(url)
                    .delete()
                    .header("Authorization", "Bearer " + properties.secretKey())
                    .header("apikey", properties.secretKey())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() && response.code() != 404) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Supabase delete failed: {} - {} for path: {}", response.code(), errorBody, objectPath);
                    // Swallow error - file cleanup failures should not break higher-level operations
                } else {
                    log.debug("Deleted file from Supabase: {}", objectPath);
                }
            }
        } catch (IOException ex) {
            log.error("Failed to delete file from Supabase: {} - {}", objectPath, ex.getMessage());
            // Swallow error - file cleanup failures should not break higher-level operations
        }
    }

    /**
     * Helper to extract strict basename and replace dangerous characters.
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "uploaded_file";
        }
        // extract basename removing any directory paths
        String baseName = Paths.get(filename).getFileName().toString();
        // replace any character that is not alphanumeric, dot, underscore, or hyphen
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * DTO for signed URL request.
     */
    private record SignedUrlRequest(int expiresIn) {
    }
}