package com.careerplatform.career_assistant.exception;

/**
 * Thrown when a call to the LLM provider fails, times out, or returns a response this
 * app cannot use. GlobalExceptionHandler maps it to 502.
 *
 * 6.3: was GeminiApiException. The provider is Groq, has been for some time, and the
 * name is now provider-neutral so the next swap does not need another rename.
 */
public class AiProviderException extends RuntimeException {
    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
