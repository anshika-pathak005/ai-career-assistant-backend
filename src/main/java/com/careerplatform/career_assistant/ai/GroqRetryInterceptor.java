package com.careerplatform.career_assistant.ai;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Retries transient Groq failures with exponential backoff and jitter.
 * Attached to the shared client in GroqHttpConfig, so every Groq call gets it.
 * Up to three attempts; the last failure is rethrown for the caller to handle.
 */
@Slf4j
public class GroqRetryInterceptor implements Interceptor {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 500;
    private static final SecureRandom JITTER = new SecureRandom();

    @Override
    public Response intercept(Chain chain) throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Response response = null;
            try {
                response = chain.proceed(chain.request());

                if (!isRetryable(response.code()) || attempt == MAX_ATTEMPTS) {
                    return response;
                }

                long waitMillis = backoffMillis(attempt, response);
                log.warn("Groq returned {} (attempt {}/{}), retrying in {}ms",
                        response.code(), attempt, MAX_ATTEMPTS, waitMillis);

                // Close the body before reusing the connection, or the pool leaks a
                // socket per retry.
                response.close();
                sleep(waitMillis);

            } catch (java.net.SocketTimeoutException ex) {
                // Not retried: the read timeout has already elapsed, so another attempt
                // just parks the thread for the same duration again.
                throw ex;
            } catch (IOException ex) {
                if (response != null) {
                    response.close();
                }
                lastFailure = ex;

                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                long waitMillis = backoffMillis(attempt, null);
                log.warn("Groq connection failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_ATTEMPTS, waitMillis, ex.getMessage());
                sleep(waitMillis);
            }
        }

        throw (lastFailure != null) ? lastFailure : new IOException("Groq call failed");
    }

    /**
     * Only 429 and 5xx are worth retrying.
     * A 400 means the request body is malformed and a 401 means the key is wrong;
     * neither improves on a second attempt, so retrying them only makes the user wait.
     */
    private boolean isRetryable(int code) {
        return code == 429 || code >= 500;
    }

    /**
     * How long to wait before the next attempt.
     * Groq's own Retry-After header wins when present, since it knows when it will
     * accept traffic again. Otherwise 500ms doubling per attempt, plus a random
     * component so that clients failing at the same moment do not all retry at the
     * same moment and knock the recovering service over again.
     */
    private long backoffMillis(int attempt, Response response) {
        if (response != null) {
            String retryAfter = response.header("Retry-After");
            if (retryAfter != null) {
                try {
                    return Long.parseLong(retryAfter.trim()) * 1000;
                } catch (NumberFormatException ignored) {
                    // header was a date, not seconds — fall through to the local backoff
                }
            }
        }
        long exponential = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));   // 500, 1000, 2000
        return exponential + JITTER.nextInt(250);
    }

    private void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while backing off before a Groq retry", ex);
        }
    }
}
