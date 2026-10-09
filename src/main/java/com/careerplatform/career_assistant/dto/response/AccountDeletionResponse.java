package com.careerplatform.career_assistant.dto.response;

import java.time.LocalDateTime;

/**
 * Returned when a deletion is scheduled (7.1).
 *
 * The endpoint used to return 204 with no body, which was accurate when the deletion
 * had already happened. It has not happened now, and the client cannot render an honest
 * confirmation screen without knowing when it will - so the date is part of the
 * response rather than something the frontend recomputes from its own clock.
 *
 * @param scheduledFor when the data will actually be destroyed
 * @param cancellable  whether the user can still stop it; always true at this point,
 *                     present so the client does not have to compare timestamps itself
 */
public record AccountDeletionResponse(LocalDateTime scheduledFor, boolean cancellable) {

    public static AccountDeletionResponse scheduled(LocalDateTime scheduledFor) {
        return new AccountDeletionResponse(scheduledFor, true);
    }
}
