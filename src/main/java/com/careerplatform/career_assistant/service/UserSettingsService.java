package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.ChangePasswordRequest;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.InvalidCredentialsException;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.entity.CareerRoadmap;
import com.careerplatform.career_assistant.repository.AnalysisResultRepository;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.ResumeRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.repository.RefreshTokenRepository;
import com.careerplatform.career_assistant.config.properties.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ResumeRepository resumeRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final CareerRoadmapRepository roadmapRepository;
    private final FileStorageService fileStorageService;
    private final AppProperties appProperties;
    private final PlatformTransactionManager transactionManager;

    /**
     * One transaction per purged account.
     *
     * A TransactionTemplate rather than @Transactional(REQUIRES_NEW) on purgeAccount,
     * for the same reason RefreshTokenService uses one: purgeDueAccounts calls
     * purgeAccount on itself, and a self-invocation never passes through the Spring
     * proxy, so the annotation would be silently ignored and every account in the batch
     * would share one transaction - or, since the scheduled method is not transactional
     * at all, would run with no transaction and fail on the first lazy association.
     */
    private TransactionTemplate purgeTx;

    @PostConstruct
    void initPurgeTransaction() {
        this.purgeTx = new TransactionTemplate(transactionManager);
        this.purgeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Changes user password.
     * Validates current password first.
     * Validates new password matches confirm password.
     * Revokes all refresh tokens — forces re-login everywhere.
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        // verify new passwords match
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidCredentialsException("New passwords do not match");
        }

        /*
         * 6.6: the new password must actually be new.
         *
         * Without this, "change your password" after a suspected compromise could be
         * satisfied by re-entering the compromised password - and it would report success
         * and revoke every session, so the user would believe they had fixed it.
         *
         * Compared against the stored hash rather than the submitted currentPassword,
         * because the two are only equal when the user typed their current password
         * correctly, and this check should hold regardless.
         */
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(
                    "New password must be different from your current password");
        }

        // update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Revoke refresh tokens so no device can mint a new access token.
        refreshTokenService.revokeAllTokens(user);

        // Invalidate the access tokens too. Revoking only the refresh tokens would leave
        // every token already in the wild working until it expired — a grace period
        // handed to whoever the user is changing their password because of.
        userRepository.bumpTokenVersion(userId);

        log.info("Password changed; access and refresh tokens revoked for userId: {}", userId);
    }

    /**
     * Schedules the account for deletion and returns when it will happen.
     *
     * 7.1: this used to destroy the data inside the request. The plan's objection was
     * that the only thing standing in front of it was a password which, by the time
     * someone can call an authenticated endpoint, they already have. A grace period does
     * not make the password stronger; it makes the outcome reversible, which is the
     * property that actually helps.
     *
     * <b>Deliberate limitation.</b> The window is only useful if the owner finds out.
     * With no mail infrastructure in this app, "finding out" means logging in and seeing
     * the pending state on their profile, so someone who does not log in during the
     * window is no better protected than before. An email at request time, at cancel
     * time and at purge time is the missing half of this control - see PHASE7-CHANGES.md.
     *
     * Sessions are deliberately NOT revoked here. The user has to be able to log in to
     * cancel, and a frozen account that cannot be unfrozen is a worse failure than a
     * pending one that can.
     */
    @Transactional
    public LocalDateTime requestAccountDeletion(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // verify password before scheduling the deletion
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Password is incorrect");
        }

        /*
         * Idempotent. A second request during the grace period must not slide the
         * deletion date forward - if it did, an attacker who kept the session alive
         * could keep pushing the date out and the account would never actually be
         * deleted, while the owner saw a pending request that never resolved.
         */
        if (user.getDeletionRequestedAt() == null) {
            user.setDeletionRequestedAt(LocalDateTime.now());
            userRepository.save(user);
        }

        LocalDateTime scheduledFor = scheduledPurgeTime(user);
        log.info("Account deletion scheduled for userId: {} at {}", userId, scheduledFor);
        return scheduledFor;
    }

    /**
     * Cancels a pending deletion.
     *
     * Requires the password again rather than just a valid session. The whole point of
     * the grace period is that a stolen session may have started the deletion; letting
     * that same session cancel it would be harmless, but letting it cancel *and then
     * re-request* is how an attacker hides the request from a user who checks once.
     * Asking for the password keeps both ends of the window behind the same control.
     *
     * Returns silently when nothing was pending, so a double-click is not an error.
     */
    @Transactional
    public void cancelAccountDeletion(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Password is incorrect");
        }

        if (user.getDeletionRequestedAt() == null) {
            return;
        }

        user.setDeletionRequestedAt(null);
        userRepository.save(user);
        log.info("Pending account deletion cancelled for userId: {}", userId);
    }

    /**
     * Destroys every account whose grace period has elapsed.
     *
     * Runs on a schedule rather than with a per-account timer, because a timer scheduled
     * in memory does not survive a restart and this is the one job whose failure is
     * invisible: nobody complains that their data was not deleted.
     *
     * Each account is purged in its own transaction. One row that fails - a foreign key
     * this code has not accounted for, a file system error - must not abandon the rest
     * of the batch, and a single transaction spanning every due account would hold locks
     * across all of them.
     */
    @Scheduled(cron = "${app.account.purge-cron:0 0 4 * * *}")
    public void purgeDueAccounts() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(appProperties.account().deletionGracePeriod());

        List<User> due = userRepository.findByDeletionRequestedAtLessThanEqual(cutoff);
        if (due.isEmpty()) {
            return;
        }

        log.info("Purging {} account(s) whose deletion grace period expired before {}",
                due.size(), cutoff);

        for (User user : due) {
            Long userId = user.getId();
            try {
                purgeTx.executeWithoutResult(status -> purgeAccount(userId));
            } catch (RuntimeException ex) {
                // Logged, not rethrown: the next run will pick this account up again.
                log.error("Failed to purge userId {}: {}", userId, ex.getMessage());
            }
        }
    }

    /**
     * Permanently deletes one account and everything that hangs off it.
     *
     * 5.4 — this used to claim to cascade and did not. Resume, AnalysisResult and
     * CareerRoadmap each hold a @ManyToOne to User with no cascade, and User has no
     * inverse collection, so userRepository.delete(user) hit a foreign key violation for
     * any user who had ever uploaded a resume or generated a roadmap. It surfaced as a
     * 500 from the catch-all handler, and only appeared to work because it was
     * presumably only ever tried on a fresh account.
     *
     * Deletion order is explicit and follows the foreign keys inwards:
     *
     *   1. resume_analysis  — resume_id and user_id are both NOT NULL
     *   2. resumes
     *   3. career_roadmaps  — via entity delete, so CascadeType.ALL + orphanRemoval
     *                          takes phases -> skills -> steps with it. A bulk JPQL
     *                          DELETE would skip cascading and orphan the whole tree.
     *   4. refresh_tokens
     *   5. users
     *
     * The second half of the old bug: even when rows went, the resume PDFs stayed on
     * disk. A user exercising deletion left their CV, name, email and phone in the
     * uploads folder. File paths are collected before the rows are deleted and the files
     * are removed after commit — not during, because a rollback after unlinking cannot
     * put the file back, and an unlink that throws must not roll back the deletion.
     *
     * 7.1: called once per due account from purgeDueAccounts, inside purgeTx so each
     * account commits or rolls back on its own. @Transactional is kept for any future
     * external caller, but it is the template that is doing the work here.
     */
    @Transactional
    public void purgeAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Read the paths while the rows still exist.
        List<String> filePaths = resumeRepository.findFilePathsByUserId(userId);

        int analyses = analysisResultRepository.deleteAllByUserId(userId);
        int resumes  = resumeRepository.deleteAllByUserId(userId);

        List<CareerRoadmap> roadmaps = roadmapRepository.findByUserId(userId);
        roadmapRepository.deleteAll(roadmaps);

        refreshTokenRepository.deleteByUserId(userId);
        userRepository.delete(user);

        log.info("Account purged for userId: {} — {} analyses, {} resumes, {} roadmaps",
                userId, analyses, resumes, roadmaps.size());

        deleteFilesAfterCommit(userId, filePaths);
    }

    /** When a pending deletion will actually execute, or null if none is pending. */
    public LocalDateTime scheduledPurgeTime(User user) {
        return appProperties.account().purgeTimeFor(user.getDeletionRequestedAt());
    }

    /**
     * Unlinks the user's uploaded files once the transaction has actually committed.
     *
     * Each failure is swallowed and logged rather than rethrown: the account is already
     * gone at this point and an afterCommit exception cannot undo that, so the only
     * useful outcome is a log line an operator can act on. A leftover file is a cleanup
     * task; a half-deleted account is a data integrity problem.
     */
    private void deleteFilesAfterCommit(Long userId, List<String> filePaths) {
        if (filePaths.isEmpty()) return;

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (String path : filePaths) {
                            try {
                                fileStorageService.deleteFile(path);
                            } catch (RuntimeException ex) {
                                log.error("Orphaned file after account deletion for userId {}: {}",
                                        userId, ex.getMessage());
                            }
                        }
                        log.info("Removed {} uploaded file(s) for deleted userId: {}",
                                filePaths.size(), userId);
                    }
                });
    }

}