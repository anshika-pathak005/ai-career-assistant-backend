# CareerPilot— Backend

The backend service for **Career Assistant**, a full-stack AI-powered career guidance platform that helps students and aspiring professionals plan and manage their career journey. This service exposes REST APIs for authentication, resume analysis, roadmap generation, and dashboard analytics.

> Frontend repository: see the companion `career-assistant-frontend` project (Next.js + TypeScript).

---

## Overview

The backend is built with **Java Spring Boot** and follows a clean, layered architecture (Controller → Service → Repository → Entity). It handles:

- Secure user authentication and session management (JWT-based, with refresh tokens)
- Guided multi-step user onboarding and profile management
- Resume upload, text extraction, and AI-powered resume analysis
- Personalized career roadmap generation based on user goals, experience, and learning style
- Progress tracking across roadmap phases and skill steps
- Dashboard aggregation for user analytics and quick stats

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java |
| Framework | Spring Boot |
| Security | Spring Security, JWT Authentication |
| Database | MySQL |
| Build Tool | Maven |
| AI Integration | Groq API (resume analysis & roadmap generation) |
| File Handling | Local file storage for resume uploads |

---

## Project Structure

```
src/main/java/com/careerplatform/career_assistant/
│
├── config/            # Security & JWT filter configuration
├── controller/         # REST API endpoints
├── dto/
│   ├── request/         # Incoming request payloads
│   └── response/        # Outgoing response payloads
├── entity/             # JPA entities (DB models)
├── enums/              # Domain enums (goal type, skill level, etc.)
├── exception/           # Custom exceptions & global exception handler
├── repository/          # Spring Data JPA repositories
├── service/             # Business logic layer
└── util/                # Utility classes (JWT, cookies, formatting)
```

---

## Core Modules

### Authentication (`AuthController`, `AuthService`, `JwtUtil`)
- Registration (multi-step: basic info → detailed profile)
- Login with JWT access & refresh tokens
- Token refresh and revocation
- Password change support

### User & Profile (`UserController`, `UserService`, `UserSettingsController`)
- Profile retrieval and updates
- Account settings management

### Resume Analysis (`ResumeController`, `ResumeProcessingService`, `ResumeGroqService`, `TextExtractionService`)
- Resume upload and storage (`FileStorageService`)
- Text extraction from uploaded documents
- AI-powered analysis: scoring, section-wise feedback, and career guidance suggestions

### Career Roadmap (`RoadmapController`, `RoadmapService`)
- AI-generated personalized roadmaps based on goal type, experience level, project level, and learning style
- Phase and skill-step breakdown
- Step completion tracking
- Generation limits per user (`GenerationLimitService`)

### Dashboard (`DashboardController`, `DashboardService`)
- Aggregated view of profile status, roadmap progress, resume summary, and quick stats
- Last activity tracking

---

## Getting Started

### Prerequisites
- Java 17+
- Maven (or use the bundled `mvnw` wrapper)
- MySQL database

### Setup

1. **Clone the repository**
   ```bash
   git clone <backend-repo-url>
   cd career-assistant
   ```

2. **Configure environment**

   Update `src/main/resources/application-local.properties` with your local settings:
   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/career_assistant
   spring.datasource.username=your_username
   spring.datasource.password=your_password

   jwt.secret=your_jwt_secret
   jwt.access-token-expiration=...
   jwt.refresh-token-expiration=...

   groq.api.key=your_groq_api_key
   ```

3. **Run the application**
   ```bash
   ./mvnw spring-boot:run
   ```

   The API will be available at `http://localhost:8080` (default Spring Boot port, adjust as configured).

4. **Build a JAR**
   ```bash
   ./mvnw clean package
   java -jar target/career-assistant-0.0.1-SNAPSHOT.jar
   ```

---

## API Endpoints (High-Level)

| Controller | Responsibility |
|---|---|
| `AuthController` | Register, login, refresh token, logout |
| `UserController` | Get/update user profile |
| `UserSettingsController` | Account settings, password change |
| `ResumeController` | Upload resume, get analysis, list resumes |
| `RoadmapController` | Generate roadmap, get roadmap, mark step complete |
| `DashboardController` | Fetch dashboard summary |

---

## Error Handling

Centralized via `GlobalExceptionHandler`, covering:
- `InvalidCredentialsException`
- `ResourceNotFoundException`
- `DuplicateResourceException`
- `TokenRevokedException`
- `RoadmapLimitExceededException`
- `FileUploadException`
- `GeminiApiException`

All errors return a consistent `ErrorResponse` payload.

---

## Roadmap (Planned Features)

- AI career chat assistant
- Job recommendations engine
- Interview preparation modules
