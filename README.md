
# CareerPilot — AI Career Assistant Backend

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-Database-4479A1?logo=mysql)](https://www.mysql.com/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?logo=apachemaven)](https://maven.apache.org/)

**Live Demo:** https://my-career-pilot.vercel.app/

**Repositories:**
- Frontend: https://github.com/anshika-pathak005/career-assitant-frontend
- Backend: https://github.com/anshika-pathak005/ai-career-assistant-backend

## 1. Introduction

CareerPilot is an AI-powered career assistance platform that helps users plan their career development, analyze resumes, and track progress toward their career goals.

This repository contains the backend application built with Java and Spring Boot. It exposes REST APIs for authentication, career roadmap generation, resume analysis, dashboard data, and user account management.

The backend integrates with the Groq API for AI-powered functionality, MySQL for relational data persistence, and Supabase Storage for resume file storage.

## 2. Features

- **User Authentication & Account Management:**
  - User registration and login.
  - JWT-based authentication.
  - Refresh token management and token revocation.
  - Profile completion and account information management.
  - Password change and account deletion functionality.
  - Authentication and authorization through Spring Security.

- **AI-Powered Career Roadmaps:**
  - Generate personalized career roadmaps using the Groq API.
  - Structure roadmaps into phases, skills, and learning steps.
  - Track roadmap progress and step completion.
  - Validate and parse AI-generated responses.
  - Enforce roadmap generation limits.

- **Resume Analysis & Processing:**
  - Accept resume uploads for analysis.
  - Extract text from supported document formats using Apache Tika.
  - Process resume content through the AI integration.
  - Parse and return structured analysis results.
  - Store resume metadata and analysis results.
  - Integrate with Supabase Storage for resume files.

- **Dashboard & Progress Tracking:**
  - Retrieve dashboard information for authenticated users.
  - Access career roadmap and progress-related data.
  - Retrieve user-specific information through protected endpoints.

- **User Profile & Settings:**
  - Retrieve and update user profile information.
  - Manage supported user settings.
  - Validate incoming requests using Jakarta Bean Validation.

- **Generation Quotas & Rate Limiting:**
  - Enforce limits on selected AI-powered operations.
  - Apply configurable rate limits to incoming requests.
  - Use a token-bucket rate-limiting mechanism.
  - Cache selected application data using Caffeine.

- **API Security & Error Handling:**
  - JWT authentication filter and protected endpoints.
  - Centralized authentication and access-denied handling.
  - Request validation and consistent error responses.
  - Login attempt protection and token revocation support.

- **Monitoring & API Documentation:**
  - Spring Boot Actuator health and metrics support.
  - Micrometer Prometheus metrics integration.
  - Custom health indicators for selected dependencies.
  - OpenAPI documentation and Swagger UI for API exploration when enabled.

## 3. Tech Stack

| Technology | Purpose |
|---|---|
| Java 21 | Backend programming language |
| Spring Boot | Application framework |
| Spring Web | REST API development |
| Spring Data JPA | Database access and ORM |
| Spring Security | Authentication and authorization |
| JJWT 0.11.5 | JWT creation and validation |
| MySQL | Relational database |
| Flyway | Version-controlled database migrations |
| Groq API | AI-powered roadmap generation and resume analysis |
| OkHttp 4.12.0 | HTTP client for external AI API requests |
| Apache Tika 2.9.2 | Resume document text extraction |
| Supabase Storage | Resume file storage |
| Caffeine | In-memory caching |
| Spring Boot Actuator | Health checks and application monitoring |
| Micrometer Prometheus | Metrics collection |
| Springdoc OpenAPI 2.8.9 | API documentation |
| Maven | Dependency management and build automation |
| JUnit and Spring Boot Test | Automated testing |

## 4. Architecture

The backend follows a layered architecture that separates HTTP handling, business logic, persistence, security, and external integrations.

### Request Flow

Frontend
-> REST Controller
-> Request DTO Validation
-> Service Layer
-> Repository / External Integration
-> Database or External Service
-> Response DTO
-> Frontend

### Major Layers

- **Controller Layer:** Exposes REST endpoints and handles incoming requests.
- **DTO Layer:** Defines request and response structures.
- **Service Layer:** Implements authentication, roadmap generation, resume processing, and business logic.
- **Repository Layer:** Uses Spring Data JPA to access persisted data.
- **Entity Layer:** Represents users, roadmaps, resume records, analysis results, and refresh tokens.
- **AI Integration Layer:** Builds prompts, sends requests to Groq, handles retries, and parses AI responses.
- **Security Layer:** Handles JWT authentication, authorization, current-user resolution, and token management.
- **Configuration Layer:** Configures Spring Security, caching, rate limiting, application properties, and API documentation.
- **Exception Handling Layer:** Converts application exceptions into structured HTTP error responses.
- **Observability Layer:** Provides health indicators, metrics, and request/user logging support.

## 5. Project Structure

```text
src/
├── main/
│   ├── java/com/careerplatform/career_assistant/
│   │   ├── ai/             # Groq integration, prompts and response parsers
│   │   ├── config/         # Security, caching, filters and configuration
│   │   ├── controller/     # REST API endpoints
│   │   ├── dto/            # Request and response DTOs
│   │   ├── entity/         # JPA entities
│   │   ├── enums/          # Domain-specific enumerations
│   │   ├── exception/      # Custom exceptions and global error handling
│   │   ├── observability/  # Health indicators, metrics and logging
│   │   ├── ratelimit/      # Rate-limiting implementation
│   │   ├── repository/     # Database repositories
│   │   ├── security/       # Current-user resolution and security helpers
│   │   ├── service/        # Business logic and integrations
│   │   ├── util/           # JWT, cookie and shared utilities
│   │   └── CareerAssistantApplication.java
│   └── resources/
│       ├── db/migration/   # Flyway SQL migrations
│       ├── prompts/        # AI system and user prompt templates
│       ├── application.properties
│       ├── application-local.properties
│       ├── application-prod.properties
│       └── application-staging.properties
└── test/
    ├── java/com/careerplatform/career_assistant/
    │   ├── integration/   # Authentication, authorization and lifecycle tests
    │   ├── support/        # Shared integration-test utilities
    │   └── unit/           # Unit tests
    └── resources/
        └── application-ci.properties
```

## 6. Prerequisites & Installation

Ensure the following are installed:

- Java Development Kit (JDK) 21.
- Maven, or use the Maven Wrapper if included in the repository.
- MySQL.
- Git.
- A Groq API key for AI-powered functionality.
- Supabase Storage credentials if using the configured remote resume storage.

### Clone the Repository

```bash
git clone https://github.com/anshika-pathak005/ai-career-assistant-backend.git
cd ai-career-assistant-backend
```

Configure the required application properties and credentials before starting the application.

## 7. Environment Variables & Configuration

The application uses Spring Boot configuration files and dedicated property classes for its external integrations.

Configure the required values for:

| Configuration | Purpose |
|---|---|
| Database connection | MySQL URL, username, and password |
| JWT configuration | Signing secret and token-related settings |
| Groq API | API credentials and AI integration settings |
| Supabase Storage | Storage credentials and bucket configuration |
| File storage | Upload and storage-related settings |
| Spring profiles | Local, staging, and production configuration |
| Server and management settings | Application port and monitoring endpoints |

Refer to the configuration examples and property classes in the repository to identify the exact property names and required values.

For local development, use the local configuration profile and provide the necessary credentials through your local environment or configuration setup.

**Security notes:**
- Never commit `.env` files, API keys, database passwords, or JWT signing secrets.
- Do not use production credentials in local development.
- Keep private credentials out of source control and public documentation.
- Use separate credentials and configuration for each deployment environment.

## 8. Database & Migrations

CareerPilot uses MySQL for relational data persistence and Flyway for version-controlled database schema migrations.

Migration files are located in:

```text
src/main/resources/db/migration/
├── V1__initial_schema.sql
└── V2__supabase_storage_update.sql
```

Flyway applies versioned migrations to maintain the database schema as the application evolves.

### Local Database Setup

1. Install and start MySQL.
2. Create a database for CareerPilot.
3. Configure the database connection properties.
4. Start the application with the appropriate profile.
5. Verify that Flyway migrations complete successfully.

Avoid manually modifying the schema in ways that conflict with existing migrations. Schema changes should be introduced through new versioned migration files.

## 9. API & Security

The backend exposes REST APIs for authentication, dashboard data, resume analysis, career roadmaps, and user account management.

### API Documentation

OpenAPI documentation and Swagger UI are provided through Springdoc. Their availability depends on the active configuration profile.

### Security Mechanisms

- **JWT Authentication:** Validates access tokens for protected API requests.
- **Refresh Tokens:** Supports issuing and managing refreshed authentication tokens.
- **Token Revocation:** Supports invalidating revoked tokens.
- **Spring Security:** Applies authentication and authorization rules.
- **Request Validation:** Validates incoming request payloads.
- **Rate Limiting:** Restricts excessive requests according to configured policies.
- **Login Attempt Protection:** Helps limit repeated unsuccessful login attempts.
- **User Context Resolution:** Resolves the authenticated user's identity for protected operations.
- **Centralized Error Handling:** Returns structured error responses for application and security failures.

AI-powered operations also use application-level generation limits to control usage.

## 10. Running & Testing

### Run Locally

Configure the database and required integration credentials, then start the application using Maven:

```bash
mvn spring-boot:run
```

If the Maven Wrapper is included, it can be used instead:

```bash
./mvnw spring-boot:run
```

On Windows, use:

```powershell
.\mvnw.cmd spring-boot:run
```

The application starts using the active Spring profile and its corresponding configuration.

### Build the Application

```bash
mvn clean package
```

This compiles the application, runs the configured build lifecycle, and packages the application as a JAR when the build succeeds.

### Run Tests

```bash
mvn test
```

The test suite includes unit and integration tests covering areas such as:

- Authentication flows.
- Account deletion lifecycle.
- Roadmap-step authorization.
- Generation quota concurrency.
- Token-bucket rate limiting.
- Health indicator behavior.

Some integration tests may require additional test configuration or supporting services. Review the test profile and test setup before running them.

## 11. Deployment

The backend is deployed on Render and communicates with the separately deployed frontend.

- **Frontend:** https://my-career-pilot.vercel.app/
- **Backend:** https://careerpilot-backend-8j85.onrender.com
- **Backend Repository:** https://github.com/anshika-pathak005/ai-career-assistant-backend
- **Frontend Repository:** https://github.com/anshika-pathak005/career-assitant-frontend

### Deployment Configuration

1. Connect or configure the backend repository in Render.
2. Configure the required database connection and integration credentials.
3. Set the appropriate production Spring profile.
4. Configure the Docker build and startup process according to the repository's deployment files.
5. Verify database connectivity and Flyway migrations.
6. Confirm application health and frontend-to-backend API communication.

Production configuration should keep credentials outside the repository and disable development-only settings where appropriate.

## 12. Author

**Anshika Pathak**

GitHub: https://github.com/anshika-pathak005
