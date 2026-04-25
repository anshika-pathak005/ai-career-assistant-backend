package com.careerplatform.career_assistant.util;

import org.springframework.stereotype.Component;

/**
 * Cleans and formats raw extracted resume text before sending to AI.
 * Raw Tika output has extra whitespace, weird characters, garbled lines.
 * Clean text = better AI analysis.
 */
@Component
public class ResumeTextFormatter {

    public String format(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";

        return rawText
                // normalize line endings
                .replaceAll("\r\n", "\n")
                .replaceAll("\r", "\n")
                // remove non-printable characters except newline and tab
                .replaceAll("[^\\x09\\x0A\\x20-\\x7E]", " ")
                // collapse 3+ consecutive newlines into 2
                .replaceAll("\n{3,}", "\n\n")
                // collapse multiple spaces into one
                .replaceAll("[ \\t]{2,}", " ")
                // trim each line
                .lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .trim();
    }
}