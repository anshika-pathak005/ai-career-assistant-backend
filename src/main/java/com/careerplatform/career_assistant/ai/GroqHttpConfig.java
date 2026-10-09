package com.careerplatform.career_assistant.ai;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The single HTTP client used for every Groq call.
 * One bean means one connection pool, one dispatcher thread pool, and one place to
 * configure timeouts or add an interceptor.
 * Retries on 429 and 5xx are attached here, so every caller gets them.
 */
@Slf4j
@Configuration
public class GroqHttpConfig {

    @Bean
    public OkHttpClient groqHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                /*
                 * Roadmap generation asks for 7000 tokens and can genuinely run past
                 * 30s, so 60 is the working ceiling. This value also bounds how long a
                 * Tomcat thread is parked per generation, so treat it as a capacity
                 * decision rather than just a networking one.
                 */
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(10))
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .addInterceptor(new GroqRetryInterceptor())
                .retryOnConnectionFailure(true)
                .build();
    }
}
