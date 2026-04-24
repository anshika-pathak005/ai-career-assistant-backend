package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.exception.FileUploadException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class TextExtractionService {

    // Tika handles both PDF and DOCX automatically
    private final Tika tika = new Tika();

    /**
     * Extracts plain text from PDF or DOCX file.
     * Tika detects file type automatically — no need to handle separately.
     */
    public String extractText(String filePath) {
        try {
            Path path = Paths.get(filePath);
            String text = tika.parseToString(path.toFile());

            if (text == null || text.trim().isEmpty()) {
                throw new FileUploadException("Could not extract text from file. File may be empty or image-based.");
            }

            log.info("Text extracted successfully from: {}", filePath);
            // limit text to 10000 chars — Gemini has token limits
            return text.length() > 10000 ? text.substring(0, 10000) : text;

        } catch (IOException ex) {
            log.error("Text extraction failed: {}", ex.getMessage());
            throw new FileUploadException("Failed to read file content.");
        } catch (org.apache.tika.exception.TikaException ex) {
            log.error("Tika parsing failed: {}", ex.getMessage());
            throw new FileUploadException("Failed to parse file.");
        }
    }
}