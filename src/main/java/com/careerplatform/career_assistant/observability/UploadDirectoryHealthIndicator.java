package com.careerplatform.career_assistant.observability;

import com.careerplatform.career_assistant.config.properties.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that resume uploads can still be written (7.2).
 *
 * Plan item 4.3 - move file storage off local disk - was not done, so uploads land on
 * the container's filesystem. That makes the upload directory a real dependency of this
 * service, and it fails in ways nothing else notices: the volume fills, the mount comes
 * back read-only after a host event, or the directory is simply absent because a new
 * deployment mounted a fresh volume.
 *
 * None of those raise an error until a user uploads a file, at which point they get a
 * 500 and we find out from them. This indicator turns all three into something a scrape
 * can see.
 *
 * It reports DOWN rather than DEGRADED, deliberately: unlike a provider outage, every
 * one of these is a local condition an operator can fix, and DOWN is what gets attention.
 * It is still kept out of the liveness group - restarting the process does not empty a
 * disk - and out of readiness, because an instance that cannot accept uploads can still
 * serve roadmaps, profiles and logins.
 */
@Slf4j
@Component("uploadDirectory")
@RequiredArgsConstructor
public class UploadDirectoryHealthIndicator implements HealthIndicator {

    /**
     * Free space below which the check fails.
     *
     * Ten times the 5MB upload ceiling. Reporting at exactly one upload's worth of space
     * left is reporting after it is too late to act.
     */
    private static final long MIN_FREE_BYTES = 50L * 1024 * 1024;

    private final FileStorageProperties properties;

    @Override
    public Health health() {
        Path dir = properties.uploadDir();

        if (!Files.isDirectory(dir)) {
            // The path is configuration, not user data, so it is safe to name.
            return Health.down()
                    .withDetail("path", dir.toString())
                    .withDetail("reason", "upload directory does not exist")
                    .build();
        }

        if (!Files.isWritable(dir)) {
            return Health.down()
                    .withDetail("path", dir.toString())
                    .withDetail("reason", "upload directory is not writable")
                    .build();
        }

        try {
            FileStore store = Files.getFileStore(dir);
            long free = store.getUsableSpace();

            Health.Builder builder = (free < MIN_FREE_BYTES) ? Health.down() : Health.up();
            return builder
                    .withDetail("path", dir.toString())
                    .withDetail("freeBytes", free)
                    .withDetail("thresholdBytes", MIN_FREE_BYTES)
                    .build();

        } catch (IOException ex) {
            return Health.down(ex).withDetail("path", dir.toString()).build();
        }
    }
}
