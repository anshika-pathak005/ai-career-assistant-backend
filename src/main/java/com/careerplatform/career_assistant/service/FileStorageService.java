package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.config.properties.FileStorageProperties;
import com.careerplatform.career_assistant.exception.FileUploadException;
import com.careerplatform.career_assistant.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /**
     * 6.4: upload-dir was a @Value String re-resolved through Paths.get() on every
     * upload. It is a Path now, resolved once at startup, and a missing property fails
     * at boot naming the property rather than on the first upload.
     */
    private final FileStorageProperties properties;

    /** 7.2: upload size distribution. See the note at the record() call below. */
    private final AppMetrics metrics;

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
     * what Tika reports depends on how far into the container it reads: it may return
     * the specific wordprocessingml type, x-tika-ooxml, or plain application/zip.
     * Listing only the specific type turns away genuine Word uploads.
     */
    private static final List<String> ALLOWED_DETECTED_TYPES = Arrays.asList(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-tika-ooxml",
            "application/x-tika-msoffice",
            "application/zip"
    );

    /*
     * 6.4: MAX_SIZE was a hardcoded 5 * 1024 * 1024 that had to be kept in step by hand
     * with spring.servlet.multipart.max-file-size. It is configuration now, but the two
     * are still separate settings that must agree - and the multipart limit is the one
     * that fires first, because Spring buffers the whole body before this runs.
     */
    private final Tika tika = new Tika();

    /**
     * Validates and saves file to disk with strict magic-bytes verification and path-traversal defense.
     * Returns the saved file path.
     */
    public String saveFile(MultipartFile file) {

        // validate file not empty
        if (file.isEmpty()) {
            throw new FileUploadException("File is empty");
        }

        // validate HTTP header file type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_DECLARED_TYPES.contains(contentType)) {
            throw new FileUploadException("Invalid file type. Only PDF and DOCX allowed");
        }

        // validate file size
        if (file.getSize() > properties.maxSizeBytes()) {
            throw new FileUploadException(
                    "File size exceeds the " + properties.maxSize() + " limit");
        }

        // magic-bytes validation using Apache Tika — defends against extension spoofing
        try {
            String detectedType = tika.detect(file.getInputStream());
            log.debug("Tika detected magic-bytes type: {}", detectedType);

            if (!ALLOWED_DETECTED_TYPES.contains(detectedType)) {
                log.warn("Magic bytes mismatch! Declared: {}, Detected: {}", contentType, detectedType);
                throw new FileUploadException("File content does not match allowed formats (PDF/DOCX)");
            }
        } catch (IOException ex) {
            log.error("Failed to inspect file magic bytes: {}", ex.getMessage());
            throw new FileUploadException("Could not verify file content type.");
        }

        try {
            // create upload directory if it doesn't exist
            Path uploadPath = properties.uploadDir();
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // sanitize filename to prevent path traversal attack (e.g. "../../etc/passwd")
            String rawFileName = file.getOriginalFilename();
            String safeFileName = sanitizeFilename(rawFileName);

            // generate unique filename — prevents overwriting
            String storedId = UUID.randomUUID().toString();
            String uniqueFileName = storedId + "_" + safeFileName;
            Path filePath = uploadPath.resolve(uniqueFileName).normalize();

            // ensure resolved path is strictly within the designated upload directory
            if (!filePath.startsWith(uploadPath.normalize())) {
                // The sanitised name, not the raw one: this branch is unreachable after
                // sanitizeFilename strips separators, and if it ever does fire, the
                // sanitised value is enough to tell you what got through.
                log.error("Path traversal attempt blocked for stored name: {}", safeFileName);
                throw new FileUploadException("Invalid file path.");
            }

            // save file to disk
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            /*
             * 6.7: this logged the full path, which ends in the sanitised ORIGINAL
             * filename - routinely "Firstname_Lastname_Resume.pdf". The stored name is
             * "<uuid>_<original>", so logging only the uuid identifies the file for an
             * operator without putting the user's name in the application log.
             */
            log.info("Resume stored: {} ({} bytes)", storedId, file.getSize());

            /*
             * 7.2: recorded after the write, not on arrival, so the distribution
             * describes files we actually accepted. Counting rejected uploads here would
             * mix "what our users send" with "what we turn away", and the p99 would be
             * dominated by the 5MB rejections rather than by real resumes.
             */
            metrics.uploadAccepted(file.getSize());

            return filePath.toString();

        } catch (IOException ex) {
            log.error("Failed to save file: {}", ex.getMessage());
            throw new FileUploadException("Could not save file. Please try again.");
        }
    }

    // deletes file from disk — called when resume is deleted
    public void deleteFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Files.deleteIfExists(path);
            // debug, not info: the path contains the original filename. See 6.7.
            log.debug("Deleted stored file");
        } catch (IOException ex) {
            log.error("Failed to delete file: {}", ex.getMessage());
        }
    }

    // helper to extract strict basename and replace dangerous characters
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "uploaded_file";
        }
        // extract basename removing any directory paths
        String baseName = Paths.get(filename).getFileName().toString();
        // replace any character that is not alphanumeric, dot, underscore, or hyphen
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}