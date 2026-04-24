package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.exception.FileUploadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class FileStorageService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    // allowed MIME types
    private static final List<String> ALLOWED_TYPES = Arrays.asList(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5MB

    /**
     * Validates and saves file to disk.
     * Returns the saved file path.
     */
    public String saveFile(MultipartFile file) {

        // validate file not empty
        if (file.isEmpty()) {
            throw new FileUploadException("File is empty");
        }

        // validate file type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new FileUploadException("Invalid file type. Only PDF and DOCX allowed");
        }

        // validate file size
        if (file.getSize() > MAX_SIZE) {
            throw new FileUploadException("File size exceeds 5MB limit");
        }

        try {
            // create upload directory if it doesn't exist
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // generate unique filename — prevents overwriting
            String uniqueFileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(uniqueFileName);

            // save file to disk
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File saved: {}", filePath);

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
            log.info("File deleted: {}", filePath);
        } catch (IOException ex) {
            log.error("Failed to delete file: {}", ex.getMessage());
        }
    }
}