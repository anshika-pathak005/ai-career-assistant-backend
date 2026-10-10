package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.exception.FileUploadException;
import com.careerplatform.career_assistant.observability.AppMetrics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Extracts plain text from an uploaded resume. Tika detects PDF or DOCX automatically.
 *
 * Uploaded files are untrusted input: anyone with an account can send one, and Tika
 * hands them to PDFBox, POI and a long tail of parsers. Extraction is therefore capped
 * at MAX_CHARS, run on a bounded pool, and given a hard timeout.
 *
 * Resumes longer than the cap are rejected rather than truncated, so a user is never
 * scored on a fragment of their CV without being told.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextExtractionService {

    /**
     * 7.2: extraction timing.
     *
     * Lombok's @RequiredArgsConstructor only takes final fields that have no initialiser,
     * so this is the constructor argument while `tika` and `extractionPool` below stay
     * as they were.
     */
    private final AppMetrics metrics;

    /**
     * Hard ceiling on extracted characters.
     * Passed to Tika so it stops reading at this point — parsing everything and
     * truncating afterwards would let a small zip bomb allocate gigabytes first.
     */
    private static final int MAX_CHARS = 10_000;

    private static final int EXTRACTION_TIMEOUT_SECONDS = 20;

    // Tika handles both PDF and DOCX automatically
    private final Tika tika = new Tika();

    /**
     * Pool the extraction runs on.
     * Bounded, so a run of malicious uploads cannot spawn unbounded threads. Daemon
     * threads, so a stuck parser never prevents the JVM shutting down.
     */
    private final ExecutorService extractionPool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "tika-extract");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Extracts text from the file at the given path.
     * Throws FileUploadException if the file cannot be read, yields no text, takes too
     * long, or is longer than MAX_CHARS.
     */
    public String extractText(String filePath) {
        /*
         * 7.2: timed here rather than inside the pool task, because the number worth
         * knowing is how long the *caller* waited - which includes queueing behind the
         * other three pool threads. A timer around the Tika call alone would look
         * healthy while uploads sat in a queue.
         *
         * Outcome is two values, not an exception class, to keep the tag bounded.
         */
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            return doExtract(filePath);
        } catch (RuntimeException ex) {
            outcome = "failure";
            throw ex;
        } finally {
            metrics.extractionCompleted(
                    Duration.ofNanos(System.nanoTime() - startNanos), outcome);
        }
    }

    /**
     * Extracts text from an InputStream.
     * Suitable for processing files from Supabase Storage or other stream sources.
     */
    public String extractText(InputStream inputStream) {
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            return doExtract(inputStream);
        } catch (RuntimeException ex) {
            outcome = "failure";
            throw ex;
        } finally {
            metrics.extractionCompleted(
                    Duration.ofNanos(System.nanoTime() - startNanos), outcome);
        }
    }

    /**
     * Extracts text from a byte array.
     * Suitable for processing files already downloaded from Supabase Storage.
     */
    public String extractText(byte[] bytes) {
        return extractText(new ByteArrayInputStream(bytes));
    }

    private String doExtract(String filePath) {
        Path path = Paths.get(filePath);

        Future<String> task = extractionPool.submit(() -> {
            try (InputStream stream = Files.newInputStream(path)) {
                return tika.parseToString(stream, new org.apache.tika.metadata.Metadata(), MAX_CHARS);
            }
        });

        return waitForExtraction(task);
    }

    private String doExtract(InputStream inputStream) {
        Future<String> task = extractionPool.submit(() -> {
            // Use a markable stream if we need to, but Tika can handle it
            return tika.parseToString(inputStream, new org.apache.tika.metadata.Metadata(), MAX_CHARS);
        });

        return waitForExtraction(task);
    }

    private String waitForExtraction(Future<String> task) {
        String text;
        try {
            // The timeout bounds the wait, not the parser thread itself — a pathological
            // file still occupies one pool thread until it finishes. The pool size is
            // what stops that growing without limit.
            text = task.get(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (TimeoutException ex) {
            task.cancel(true);
            // 6.7: the path ends in the sanitised original filename, which is routinely
            // the user's real name. Logged at debug only; the warn line carries no PII.
            log.warn("Text extraction timed out after {}s", EXTRACTION_TIMEOUT_SECONDS);
            throw new FileUploadException(
                    "This file took too long to read. It may be corrupted or too complex.");

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FileUploadException("File processing was interrupted. Please try again.");

        } catch (java.util.concurrent.ExecutionException ex) {
            // Log the cause, tell the user nothing about it — parser stack traces leak
            // library versions, which is a map of what to attack next.
            log.error("Text extraction failed: {}", ex.getCause().toString());
            throw new FileUploadException("Could not read this file. Please upload a valid PDF or DOCX.");
        }

        validateExtractedText(text);
        return text;
    }

    private void validateExtractedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new FileUploadException(
                    "No text could be extracted. The file may be empty or a scanned image.");
        }

        // Hitting the cap means Tika stopped early, so the resume is longer than we
        // will analyse. Reject rather than silently scoring a fragment.
        if (text.length() >= MAX_CHARS) {
            throw new FileUploadException(
                    "This resume is longer than " + MAX_CHARS + " characters. "
                            + "Please upload a shorter version (1-2 pages works best).");
        }

        log.info("Extracted {} characters from uploaded file", text.length());
    }

    @PreDestroy
    void shutdown() {
        extractionPool.shutdownNow();
    }
}