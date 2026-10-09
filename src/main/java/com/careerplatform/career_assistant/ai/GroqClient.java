package com.careerplatform.career_assistant.ai;

import com.careerplatform.career_assistant.config.properties.GroqProperties;
import com.careerplatform.career_assistant.exception.AiProviderException;
import com.careerplatform.career_assistant.observability.AppMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Communicates with the Groq API.
 * Accepts a request body and returns the assistant's message content.
 *
 * Response parsing and validation are handled by the calling service,
 * as each feature may expect a different response structure.
 *
 * The endpoint is provided as a parameter, allowing the same client
 * to communicate with different Groq accounts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroqClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final ObjectMapper objectMapper;

    /** Shared client with retries, timeouts and one connection pool — see GroqHttpConfig. */
    private final OkHttpClient httpClient;

    /** 7.2: latency and outcome per call, plus the state GroqHealthIndicator reads. */
    private final AppMetrics metrics;

   /**
 * Sends a chat-completion request to Groq and returns the assistant's message content.
 *
 * @param operation identifier used for AI request metrics
 * @param endpoint Groq endpoint configuration containing the URL and API key
 * @param requestBody serialized chat-completion request
 * @return the assistant's message content
 * @throws AiProviderException if the request fails or the response cannot be processed
 */
    public String complete(String operation, GroqProperties.Endpoint endpoint, String requestBody) {
        Request request = new Request.Builder()
                .url(endpoint.url())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + endpoint.key())
                .post(RequestBody.create(requestBody, JSON))
                .build();

        /*
         * 7.2: the timer wraps the whole call, retries included, because that is the
         * number that matters - a user waiting on a roadmap waits for the retries too.
         * The per-attempt view is in GroqRetryInterceptor's warn lines.
         *
         * The sample is stopped on every path. A timer that only records successes
         * reports that the AI got faster during an outage, which is exactly backwards.
         */
        Timer.Sample sample = metrics.startAiCall();
        String outcome = "unknown";

        try (Response response = httpClient.newCall(request).execute()) {
            outcome = outcomeFor(response.code());
            return handleResponse(response);

        } catch (AiProviderException ex) {
            /*
             * A 200 whose body we could not use - an empty choices array, a blank
             * content field, an unparseable envelope. Without this the call would be
             * tagged "success" because the status line said so, and the dashboard would
             * show a healthy provider while every generation failed.
             */
            if ("success".equals(outcome)) {
                outcome = "unreadable_response";
            }
            throw ex;

        } catch (IOException ex) {
            outcome = "connection_error";
            log.error("Groq connection failed: {}", ex.getMessage());
            throw new AiProviderException("Failed to connect to AI service. Please try again.", ex);

        } finally {
            metrics.stopAiCall(sample, operation, outcome);
        }
    }

    /**
     * Turns a status code into a bounded tag value.
     *
     * Grouped rather than used raw so an unexpected status cannot create a new time
     * series per code, and so "5xx" is one line on a graph rather than four.
     */
    private String outcomeFor(int code) {
        if (code == 200) return "success";
        if (code == 429) return "rate_limited";
        if (code == 401 || code == 403) return "auth_failed";
        if (code >= 500) return "provider_error";
        return "client_error";
    }

    /**
     * Maps HTTP status to a user-facing message.
     * 200 unwraps the envelope; everything else throws with a message safe to show a user.
     */
    private String handleResponse(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";

        switch (response.code()) {
            case 200 -> {
                return extractContent(body);
            }
            case 400 -> {
                log.error("Groq bad request: {}", body);
                throw new AiProviderException("Invalid request sent to AI service.");
            }
            case 401, 403 -> {
                // The key itself is never logged — it would end up in the log aggregator.
                log.error("Groq rejected our credentials ({})", response.code());
                throw new AiProviderException("AI service authentication failed. Contact support.");
            }
            case 413 -> {
                log.warn("Groq payload too large");
                throw new AiProviderException("Input is too large to process. Please shorten it.");
            }
            case 429 -> {
                // GroqRetryInterceptor has already exhausted its retries by this point.
                log.warn("Groq rate limit hit");
                throw new AiProviderException("AI service is busy. Please try again in a moment.");
            }
            case 500, 502, 503, 504 -> {
                log.error("Groq server error: {}", response.code());
                throw new AiProviderException("AI service temporarily unavailable. Please try again.");
            }
            default -> {
                log.error("Unexpected Groq response: {} — {}", response.code(), body);
                throw new AiProviderException("Unexpected error from AI service.");
            }
        }
    }

    /**
     * Unwraps {@code choices[0].message.content} and strips markdown fences.
     *
     * 6.2 bug fix: this was {@code root.path("choices").get(0)}. On an empty choices
     * array — which Groq does return, for instance when a content filter fires — get(0)
     * returns Java null, and the {@code .path("message")} that followed threw a
     * NullPointerException. That NPE escaped as a 500 rather than the 502 this class is
     * supposed to produce. {@code .path(0)} returns a missing node instead, which flows
     * through to the blank check below.
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            String content = root
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

            if (content == null || content.isBlank()) {
                log.error("Groq returned no message content");
                throw new AiProviderException("AI returned empty response. Please try again.");
            }

            return stripFences(content);

        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to read Groq response envelope: {}", ex.getMessage());
            throw new AiProviderException("AI returned unreadable response. Please try again.", ex);
        }
    }

    /**
     * Removes ```json fences the model sometimes wraps JSON in despite being asked for a
     * json_object response.
     */
    private String stripFences(String content) {
        return content
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
    }
}
