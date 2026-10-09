package com.careerplatform.career_assistant.ai;

import com.careerplatform.career_assistant.exception.AiProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prompt templates from the classpath and replaces named placeholders
 * with the values provided by the calling service.
 *
 * Templates are stored as text files in the prompts/ directory and packaged
 * with the application.
 *
 * Named placeholders use the {{name}} format, making templates easier to
 * read and maintain without relying on positional arguments.
 *
 * Each placeholder is replaced once. Replacement values are inserted as-is
 * and are not processed again for additional placeholders.
 */
@Slf4j
@Component
public class PromptTemplateService {

    private static final String TEMPLATE_ROOT = "prompts/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Templates are immutable on the classpath, so they are read once and kept.
     * A ConcurrentHashMap rather than @Cacheable: this is a private detail of this class
     * and does not need a cache manager, eviction policy or a name in CacheConfig.
     */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

        /**
         * Loads a prompt template and replaces its named placeholders with
         * the corresponding values.
         *
         * Each placeholder must have a matching key in the provided map.
         * Null values are replaced with empty strings, and replacement values
         * are inserted literally without being interpreted as placeholders.
         *
         * @param name the template file name without its .txt extension
         * @param values a map of placeholder names to replacement values
         * @return the rendered prompt text
         * @throws AiProviderException if the template is unavailable or a
         *         required placeholder value is missing
         */
    public String render(String name, Map<String, String> values) {
        String template = load(name);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);

            if (!values.containsKey(key)) {
                /*
                 * Fail loudly. The alternative - leaving "{{dreamJob}}" in the prompt -
                 * still produces a confident, well-formed roadmap, just one built around
                 * a placeholder the model silently interpreted. That is the kind of bug
                 * you find in a user complaint three weeks later.
                 */
                throw new AiProviderException(
                        "Prompt template '" + name + "' needs a value for {{" + key + "}}");
            }

            String value = values.get(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);

        return out.toString();
    }

    /** Reads a template off the classpath, caching it after the first read. */
    private String load(String name) {
        return cache.computeIfAbsent(name, key -> {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_ROOT + key + ".txt");

            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                // A missing template is a packaging error, not a user error.
                log.error("Prompt template not found on classpath: {}{}.txt", TEMPLATE_ROOT, key);
                throw new AiProviderException("AI prompt configuration is missing.", ex);
            }
        });
    }
}
