package com.careerplatform.career_assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 6.4: finds the @ConfigurationProperties records under config.properties. Without it
// they are never bound and every setting silently reads as null or zero.
@ConfigurationPropertiesScan
// Required for @Scheduled methods to run at all - without it they are silently ignored.
// Currently used by RefreshTokenService.purgeExpiredTokens.
@EnableScheduling
public class CareerAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(CareerAssistantApplication.class, args);
	}

}
