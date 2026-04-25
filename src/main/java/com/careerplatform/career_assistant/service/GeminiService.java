//package com.careerplatform.career_assistant.service;
//
//import com.careerplatform.career_assistant.exception.GeminiApiException;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.*;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.util.concurrent.TimeUnit;
//
//@Slf4j
//@Service
//public class GeminiService {
//
//    @Value("${gemini.api.key}")
//    private String apiKey;
//
//    @Value("${gemini.api.url}")
//    private String apiUrl;
//
//    private final OkHttpClient httpClient = new OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(60, TimeUnit.SECONDS) // Gemini can take time
//            .build();
//
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    /**
//     * Sends resume text to Gemini and returns raw JSON string response.
//     * Prompt instructs Gemini to return ONLY valid JSON — no extra text.
//     */
//    public String analyzeResume(String resumeText) {
//        String prompt = buildPrompt(resumeText);
//        String requestBody = buildRequestBody(prompt);
//
//        log.info("Sending resume to Gemini for analysis");
//
//        Request request = new Request.Builder()
//                .url(apiUrl + "?key=" + apiKey)
//                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
//                .build();
//
//        try (Response response = httpClient.newCall(request).execute()) {
//
//            if (!response.isSuccessful()) {
//                log.error("Gemini API returned error: {}", response.code());
//                throw new GeminiApiException("Gemini API call failed with status: " + response.code());
//            }
//
//            String responseBody = response.body().string();
//            return extractTextFromGeminiResponse(responseBody);
//
//        } catch (IOException ex) {
//            log.error("Gemini API call failed: {}", ex.getMessage());
//            throw new GeminiApiException("Failed to connect to Gemini API");
//        }
//    }
//
//    // extracts the actual text content from Gemini's nested response structure
//    private String extractTextFromGeminiResponse(String rawResponse) {
//        try {
//            JsonNode root = objectMapper.readTree(rawResponse);
//            String text = root
//                    .path("candidates").get(0)
//                    .path("content")
//                    .path("parts").get(0)
//                    .path("text")
//                    .asText();
//
//            // Gemini sometimes wraps JSON in ```json ``` — strip it
//            text = text.replaceAll("```json", "").replaceAll("```", "").trim();
//            log.info("Gemini response extracted successfully");
//            return text;
//
//        } catch (Exception ex) {
//            log.error("Failed to parse Gemini response: {}", ex.getMessage());
//            throw new GeminiApiException("Unexpected response format from Gemini");
//        }
//    }
//
//    private String buildPrompt(String resumeText) {
//        return """
//                Analyze this resume and return ONLY valid JSON with no explanation, no markdown, no extra text.
//                Return exactly this structure:
//                {
//                  "fullName": "extracted full name",
//                  "email": "extracted email",
//                  "phone": "extracted phone",
//                  "overallScore": 0-100,
//                  "atsScore": 0-100,
//                  "summary": "2 sentence overall analysis",
//                  "strengths": ["strength1", "strength2"],
//                  "improvements": ["improvement1", "improvement2"],
//                  "missingSkills": ["skill1", "skill2"],
//                  "missingKeywords": ["keyword1", "keyword2"],
//                  "experienceLevel": "Entry or Mid or Senior",
//                  "experienceYears": 0.0,
//                  "currentRole": "current job title",
//                  "technicalSkills": ["skill1", "skill2"],
//                  "softSkills": ["skill1", "skill2"]
//                }
//
//                Resume text:
//                """ + resumeText;
//    }
//
//    private String buildRequestBody(String prompt) {
//        // Gemini API request structure
//        return """
//                {
//                  "contents": [
//                    {
//                      "parts": [
//                        {
//                          "text": "%s"
//                        }
//                      ]
//                    }
//                  ],
//                  "generationConfig": {
//                    "temperature": 0.1,
//                    "responseMimeType": "application/json"
//                  }
//                }
//                """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));
//    }
//}