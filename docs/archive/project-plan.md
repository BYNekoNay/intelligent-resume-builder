# SUPERSEDED — Intelligent Resume Builder Development Plan

> Status: archived, non-authoritative, do not implement.
>
> This early plan is retained only for historical context. It conflicts with the current MVP, asynchronous task, provenance, privacy, and private PDF contracts. Implementers and coding agents must use `docs/01-项目开发总计划.md` through `docs/12-面向AI代理的开发流程与交付规范.md` instead.

## 1. Document Positioning

This document was an early development draft for the `intelligent-resume-builder` project and is no longer executable.
Its purpose is not only to describe the idea, but to guide actual implementation, iteration,
and delivery.

Target outcome:

- Build a usable intelligent resume platform around resume editing, JD matching, AI optimization,
  and PDF export
- Keep the first version controllable in scope and easy to land
- Preserve enough technical depth for interviews, demos, and future expansion

---

## 2. Product Goal

### 2.1 Core Problem

Job seekers often struggle with:

- writing resumes tailored to a job description
- making resume language concise and professional
- understanding how well a resume matches a target role
- maintaining multiple resume versions for different positions

### 2.2 Product Goal

Build a platform that lets users:

- create and manage structured resumes
- optimize resume content with AI based on a target JD
- view a match score and optimization suggestions
- export polished resumes as PDF

### 2.3 Project Goal

This project should be:

- actually developable by one person or a small team
- suitable for phased delivery
- architected for evolution, not over-designed from day one
- strong enough to present as a backend-focused AI project

---

## 3. Development Strategy

### 3.1 Overall Principle

Adopt a **modular monolith first, service evolution later** strategy.

Reason:

- the business model is still being validated
- the core value lies in AI optimization and matching, not microservice governance
- an early split into many services would increase development and debugging cost

### 3.2 Architecture Evolution Path

#### Phase A: MVP

- frontend and backend separated
- backend uses Spring Boot modular monolith
- single MySQL instance
- Redis used only where needed
- AI calls made synchronously first

#### Phase B: Growth

- add async tasks for PDF generation and heavy AI jobs
- add Redis caching and rate limiting
- add observability and operation dashboards

#### Phase C: Service Split

When traffic or complexity becomes real, split modules gradually:

- AI module
- PDF export module
- scoring and matching module

This keeps the project realistic and easier to maintain.

---

## 4. Scope Definition

### 4.1 MVP Scope

The first release should include only the following:

1. user registration and login
2. structured resume CRUD
3. multi-resume management
4. JD input and storage
5. AI optimization based on resume + JD
6. resume and JD match scoring
7. PDF export

### 4.2 Deferred Features

The following are explicitly not part of MVP:

- RAG
- vector database
- microservice split
- complex workflow engine
- enterprise multi-tenant support
- Kubernetes deployment
- advanced admin console

This boundary is important. It prevents the project from becoming too large too early.

---

## 5. User Roles and Main Use Cases

### 5.1 Roles

#### Candidate

- register and log in
- create and edit resume
- create multiple resume versions
- paste or upload a JD
- request AI optimization
- view match score and suggestions
- export PDF

#### Admin (optional in phase 2+)

- review AI prompt templates
- manage export templates
- monitor task execution and failures

### 5.2 Core User Flow

1. user logs in
2. user creates a base resume
3. user inputs a JD
4. system parses JD and extracts keywords
5. user chooses "optimize for this JD"
6. AI produces optimized content and suggestions
7. scoring engine evaluates current resume against the JD
8. user saves a new resume version
9. user exports the selected version to PDF

---

## 6. Business Modules

### 6.1 Auth Module

Responsibilities:

- user registration
- login
- JWT issuance
- token refresh and logout
- basic permission checks

Suggested tech:

- Spring Boot
- Spring Security
- JWT
- Redis optional for token blacklist / session control

### 6.2 Resume Module

Responsibilities:

- resume CRUD
- structured data storage
- multi-version management
- template association

Key point:

- use `JSON Resume` as the base structure
- keep extension fields in the platform domain model

### 6.3 JD Module

Responsibilities:

- save target job descriptions
- parse core information from JD
- extract keywords, skills, and role requirements

Outputs:

- role title
- skill keywords
- education / experience expectations
- optional seniority tags

### 6.4 AI Optimization Module

Responsibilities:

- polish resume content
- rewrite bullet points
- tailor summary and project descriptions to a JD
- generate optimization suggestions

Inputs:

- structured resume
- target JD
- selected optimization mode

Outputs:

- optimized resume content
- explanation of changes
- warning items if information is missing

### 6.5 Match Scoring Module

Responsibilities:

- calculate resume-to-JD match score
- explain the score
- identify missing keywords and weak sections

Scoring principle:

- do not rely on the LLM alone
- combine rule-based scoring with AI-assisted explanation

Recommended scoring dimensions:

- keyword coverage
- required skill match
- project relevance
- experience relevance
- education fit
- language quality

### 6.6 PDF Export Module

Responsibilities:

- render structured resume into HTML
- generate PDF from HTML template
- support multi-template export

Recommended implementation:

- backend submits export task
- Node.js + Puppeteer handles PDF rendering
- return downloadable file URL after task completion

---

## 7. Technical Architecture

### 7.1 Recommended Stack

#### Frontend

- Vue 3
- TypeScript
- Pinia
- Element Plus or Naive UI

#### Backend

- Java 17+
- Spring Boot 3.x
- Spring Security
- Spring AI
- MyBatis Plus or JPA
- Validation

#### Storage and Middleware

- MySQL 8
- Redis
- RocketMQ or RabbitMQ for async tasks in phase 2

#### Export Engine

- Node.js
- Puppeteer

#### Observability

- OpenTelemetry
- Prometheus
- Grafana
- Loki or ELK as optional extension

### 7.2 Backend Module Layout

Recommended package layering:

```text
com.example.resume
├─ auth
├─ user
├─ resume
├─ resumeversion
├─ job
├─ ai
├─ scoring
├─ pdf
├─ common
└─ infrastructure
```

Recommended engineering layering inside each module:

- controller
- service
- domain
- repository
- dto
- mapper

---

## 8. Data Design

### 8.1 Core Entities

#### user

- id
- username
- email
- password_hash
- status
- created_at
- updated_at

#### resume

- id
- user_id
- title
- base_resume_json
- current_version_id
- created_at
- updated_at

#### resume_version

- id
- resume_id
- version_no
- source_type
- resume_json
- optimization_summary
- created_at

`source_type` examples:

- MANUAL
- AI_OPTIMIZED
- JD_CUSTOMIZED

#### job_description

- id
- user_id
- title
- company_name
- jd_text
- parsed_keywords_json
- created_at

#### match_result

- id
- resume_version_id
- job_description_id
- total_score
- keyword_score
- skill_score
- experience_score
- explanation_json
- created_at

#### ai_task

- id
- task_type
- user_id
- resume_version_id
- job_description_id
- status
- prompt_version
- result_json
- error_message
- created_at
- updated_at

#### export_task

- id
- resume_version_id
- template_code
- status
- file_url
- error_message
- created_at
- updated_at

### 8.2 Resume Structure

Use JSON Resume as the base schema:

```json
{
  "basics": {},
  "work": [],
  "education": [],
  "skills": [],
  "projects": [],
  "certificates": []
}
```

Recommended extension fields should be stored outside the standard structure when possible:

- matchSummary
- aiMetadata
- versionMetadata

This keeps the standard schema cleaner and easier to reuse.

---

## 9. API Planning

### 9.1 Auth APIs

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`

### 9.2 Resume APIs

- `POST /api/resumes`
- `GET /api/resumes`
- `GET /api/resumes/{id}`
- `PUT /api/resumes/{id}`
- `DELETE /api/resumes/{id}`
- `GET /api/resumes/{id}/versions`
- `POST /api/resumes/{id}/versions`
- `GET /api/resume-versions/{id}`

### 9.3 JD APIs

- `POST /api/jobs`
- `GET /api/jobs`
- `GET /api/jobs/{id}`
- `POST /api/jobs/{id}/parse`

### 9.4 AI APIs

- `POST /api/ai/optimize`
- `POST /api/ai/rewrite-summary`
- `POST /api/ai/rewrite-project`
- `GET /api/ai/tasks/{id}`

### 9.5 Scoring APIs

- `POST /api/scoring/match`
- `GET /api/scoring/results/{id}`

### 9.6 Export APIs

- `POST /api/exports/pdf`
- `GET /api/exports/tasks/{id}`
- `GET /api/exports/files/{id}`

---

## 10. AI Design

### 10.1 AI Usage Principles

The AI module must be useful, controllable, and explainable.

Rules:

1. AI should optimize expression, not fabricate experience
2. AI output should preserve user facts
3. every optimization task should keep original and optimized versions
4. prompt templates must be versioned
5. key outputs should be structured where possible

### 10.2 Initial AI Scenarios

#### Scenario A: Resume Polish

Goal:

- improve wording without changing facts

#### Scenario B: JD Tailoring

Goal:

- emphasize experience related to a target position

#### Scenario C: Summary Generation

Goal:

- produce a professional self-summary using resume data and target role

#### Scenario D: Suggestion Generation

Goal:

- identify missing skills, missing metrics, or weak project phrasing

### 10.3 Prompt Strategy

Recommended prompt layers:

- system prompt: define role and output constraints
- task prompt: define current optimization goal
- input payload: structured resume + JD + user intent

Prompt requirements:

- forbid fabricated facts
- ask for concise output
- return structured JSON whenever possible
- keep field-level mapping for later review

### 10.4 Model Strategy

Support a provider abstraction so the project can switch models:

- OpenAI
- Qwen
- DeepSeek

Recommended abstraction:

- provider config
- model routing
- timeout and retry policy
- prompt version registry

---

## 11. Scoring Design

### 11.1 Scoring Formula

Recommended initial total score:

```text
Total Score = 30% keyword coverage
            + 30% skill match
            + 20% project relevance
            + 10% experience relevance
            + 10% writing quality
```

This formula should be configurable, not hardcoded in many places.

### 11.2 Explanation Design

Each score must provide:

- overall score
- strongest matching points
- missing or weak areas
- recommended improvement direction

Example:

- matched: Java, Spring Boot, MySQL
- partially matched: Redis, Docker
- missing: microservice experience, CI/CD keywords

### 11.3 Implementation Strategy

Phase 1:

- rule-based extraction and scoring

Phase 2:

- LLM generates human-readable explanation based on scoring result

Phase 3:

- semantic similarity enhancement if needed

---

## 12. PDF Export Design

### 12.1 Rendering Path

```text
resume JSON -> HTML template -> Puppeteer -> PDF file
```

### 12.2 Template Strategy

At least two templates should be planned:

- classic professional
- modern concise

### 12.3 Export Mode

Phase 1:

- synchronous export acceptable for small loads

Phase 2:

- async export through MQ
- retry on failure
- task progress query

---

## 13. Security and Privacy

This project handles personal data, so security should be part of the base design.

Requirements:

- password hashing with BCrypt or stronger
- JWT authentication
- permission checks on all user-owned resources
- export file access control
- sensitive data masking in logs
- avoid sending unnecessary PII to AI providers

Recommended enhancements:

- encrypt critical fields if needed
- record AI request trace IDs instead of raw sensitive payloads in logs
- define data retention policy for generated files

---

## 14. Non-Functional Requirements

### 14.1 Performance

MVP target:

- common CRUD APIs < 300 ms
- scoring API < 2 s
- AI optimization API < 10 s depending on model
- PDF export < 15 s for synchronous mode

### 14.2 Availability

- basic exception handling
- retry for external AI calls
- timeout and fallback messages

### 14.3 Observability

At minimum:

- request logs
- error logs
- task status logs

Recommended later:

- tracing across AI task, scoring, and export
- dashboard for task success rate and latency

---

## 15. Development Milestones

### 15.1 Phase 1: Foundation

Goal:

- make the system usable without AI

Tasks:

- initialize frontend and backend projects
- build auth module
- build resume CRUD
- define JSON Resume storage model
- complete basic pages and APIs

Deliverable:

- users can register, log in, create, edit, and view resumes

### 15.2 Phase 2: Core Capability

Goal:

- deliver the central product value

Tasks:

- add JD management
- implement AI optimization flow
- implement match scoring
- implement version snapshots

Deliverable:

- users can optimize a resume for a JD and save the result as a new version

### 15.3 Phase 3: Export and Experience

Goal:

- improve usability and output quality

Tasks:

- integrate PDF export
- add resume templates
- add optimization summary and score explanation

Deliverable:

- users can export the selected resume version to PDF

### 15.4 Phase 4: Engineering Enhancement

Goal:

- improve robustness and project depth

Tasks:

- add Redis caching
- add async tasks and MQ
- add observability
- add rate limiting

Deliverable:

- system is more stable under load and easier to observe

---

## 16. Task Breakdown

### 16.1 Backend Priority Tasks

P0:

- auth module
- resume CRUD
- resume versioning
- JD storage
- AI optimization API
- scoring API

P1:

- PDF export
- async task table and status query
- Redis caching

P2:

- MQ
- observability
- provider switching

### 16.2 Frontend Priority Tasks

P0:

- login/register page
- resume editor page
- resume list page
- JD input page
- optimization result comparison page

P1:

- score result page
- version history page
- export center page

P2:

- template management
- operation dashboards

---

## 17. Testing Strategy

### 17.1 Backend

- unit tests for scoring logic
- controller integration tests for auth and resume APIs
- AI response parsing tests
- export task flow tests

### 17.2 Frontend

- form validation tests
- core flow E2E tests:
  - login
  - create resume
  - optimize by JD
  - export PDF

### 17.3 Manual Acceptance Checklist

- can create a resume from scratch
- can save multiple versions
- can submit a JD
- can generate optimized content
- can view match score and explanation
- can export a PDF successfully

---

## 18. Risks and Mitigation

| Risk | Description | Mitigation |
| --- | --- | --- |
| AI output instability | output quality differs by model and prompt | version prompts, keep structured output, support manual review |
| project scope too large | too many advanced features may block delivery | enforce MVP scope and phased delivery |
| PDF layout complexity | export layout can take more time than expected | start with 1 stable template, expand later |
| scoring trust issue | users may not trust opaque scores | make scoring explainable and partially rule-based |
| external model latency | AI calls may be slow or fail | timeout, retry, status tracking, fallback messaging |

---

## 19. Acceptance Criteria

The first version is considered complete when:

1. users can complete registration and login
2. users can create and manage structured resumes
3. users can save at least one optimized version for a target JD
4. the system returns a visible match score with explanation
5. users can export a readable PDF resume
6. the main flow works end-to-end without manual database intervention

---

## 20. Suggested Repository Planning

Recommended top-level structure:

```text
project-root/
├─ docs/
│  └─ project-plan.md
├─ frontend/
├─ backend/
├─ pdf-service/
└─ scripts/
```

If you want to keep everything simpler at first:

```text
project-root/
├─ docs/
├─ web/
└─ server/
```

Both are acceptable. The simpler one is better for early delivery.

---

## 21. Final Recommendation

The best practical route for this project is:

1. build a modular monolith first
2. lock the MVP around resume, JD, AI optimization, scoring, and PDF export
3. make scoring explainable rather than overly intelligent
4. treat observability, MQ, and service split as later engineering upgrades

This path keeps the project:

- feasible to build
- meaningful to use
- easy to explain in interviews
- expandable in later iterations

---

## 22. Next Action List

Recommended immediate next steps:

1. create backend and frontend project skeletons
2. define database tables for user, resume, resume_version, and job_description
3. implement auth and resume CRUD first
4. define the AI prompt contract and API input/output format
5. implement a first scoring version using rules
6. add PDF export after the core flow is stable

If you follow this order, development risk will stay much lower while the core value becomes visible early.
