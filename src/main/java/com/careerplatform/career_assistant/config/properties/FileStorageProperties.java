package com.careerplatform.career_assistant.config.properties;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Where uploaded resumes are written, bound from the {@code file.} prefix.
 *
 * {@code uploadDir} is a Path rather than a String, so the value is resolved once at
 * startup instead of on every upload, and FileStorageService no longer has to remember
 * to call {@code Paths.get()} before comparing it against a resolved child path.
 *
 * {@code maxSize} was a hardcoded {@code 5 * 1024 * 1024} constant in FileStorageService
 * that had to be kept in step by hand with
 * {@code spring.servlet.multipart.max-file-size}. It is configuration now, but the two
 * are still separate settings and still need to agree - see the note in
 * application.properties. The multipart limit is the one that fires first.
 */
@Validated
@ConfigurationProperties(prefix = "file")
public record FileStorageProperties(

        @NotNull(message = "file.upload-dir must be set")
        Path uploadDir,

        @NotNull @DefaultValue("5MB") DataSize maxSize) {

    public long maxSizeBytes() {
        return maxSize.toBytes();
    }
}
