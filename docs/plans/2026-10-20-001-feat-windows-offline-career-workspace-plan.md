---
title: ZhiLi Windows Offline Career Workspace - Plan
type: feat
date: 2026-10-20
topic: windows-offline-career-workspace
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
execution: code
---

# ZhiLi Windows Offline Career Workspace - Plan

## Goal Capsule

- **Objective:** Define the business flows and product requirements for rebuilding ZhiLi as a Windows desktop job-seeking workspace, with the existing web experience remaining available online.
- **Product authority:** This contract records the decisions confirmed in the product discussion. Existing implementation and historical documents are context, not binding requirements.
- **Product flow decisions:** Application statuses, local reminder behavior, and backup restore semantics have been confirmed and are specified below.
- **Technical planning items:** Select the supported AI API protocol, local credential protection, reminder delivery mechanism, and export package versioning after the product flows are confirmed.

## Product Contract

### Summary

ZhiLi will help an individual job seeker turn verified career facts into a trustworthy resume tailored to a target job, then track the application through interview practice and outcome review. The Windows desktop workspace works locally without sign-in; AI is optional and uses an API configured by the user.

### Problem Frame

The current product has grown from resume tailoring into a broader job-seeking workflow. A rebuild needs a clear product center and explicit offline behavior before architecture and implementation are chosen.

The product must keep career facts, job-specific resume versions, and application outcomes connected without letting AI invent facts or silently change a user's source material. The web and desktop experiences also need a deliberate data-transfer path because desktop data is local and will not sync automatically.

### Key Decisions

- **Resume-first, full job-seeking loop:** A trustworthy, job-specific resume is the primary outcome. Application tracking, communication drafts, interview practice, and outcome review support that outcome in the first release.
- **Local desktop workspace:** The Windows desktop app does not require sign-in and keeps its work data on one device. The web app remains an online experience and requires sign-in.
- **AI is optional:** Users provide their own AI API configuration. Manual resume tailoring and local interview practice remain usable without an AI connection.
- **User-confirmed facts:** AI may recommend materials and draft content, but users confirm source materials and review generated content before it becomes a resume version or changes the career library.
- **Explicit data transfer:** Web-to-desktop migration uses a user-triggered full-workspace export and import. It is not synchronization.
- **PDF delivery:** The first release exports PDF. Editable DOCX and online sharing are deferred.

### Actors

- A1. Job seeker: imports and maintains career facts, tailors resumes, tracks applications, practices interviews, and reviews outcomes.
- A2. Web account holder: uses the online web app and exports their workspace after re-authenticating with the account password.
- A3. User-configured AI provider: receives only task-relevant data when the user initiates an AI action.
- A4. Windows desktop app: stores and processes the local workspace, imports transfer packages, and creates backups.

### Requirements

**Workspace and source materials**

- R1. The desktop app must open directly into a local workspace without requiring an account sign-in.
- R2. The web app must continue to require account sign-in.
- R3. A job seeker must be able to start from an empty workspace or import an existing resume in DOCX, text-based PDF, or TXT format.
- R4. Resume import must parse candidate profile and career-material records locally into a temporary review area. Only user-confirmed candidates enter the career library. Cancel, parse failure, or completion must remove the temporary source file and unconfirmed parse data; the original file must not be retained by default.
- R5. Scanned-PDF OCR, image resumes, and batch import are outside the first-release import scope.
- R6. The career library must remain independent from job-specific resumes and provide reusable, user-maintained profile, work, project, education, skill, certification, language, and achievement evidence.

**Job-specific resume creation**

- R7. A user must be able to add a target job by pasting or importing JD text; job-board scraping is not required.
- R8. Each JD must have an independent tailored resume with its own version history, while the career library remains the reusable source of facts.
- R9. The system must support manual material selection and editing without AI, and may provide optional AI material recommendations.
- R10. The system must identify JD requirements that lack supporting career facts and let the user add truthful details before continuing.
- R11. Generated resume content must be reviewed at experience or field level, allowing the user to accept, edit, or reject each item before creating a version.
- R12. Users must be able to edit and preview a resume version, run an optional ATS diagnosis, and export PDF without ATS blocking export.
- R13. Resume generation and editing must preserve traceability from accepted content to the confirmed career materials where available.

**Applications and communications**

- R14. A user must be able to create and manually update an application record with status, dates, notes, outcome, and a local reminder.
- R15. Each application record must reference its target JD and the exact resume version used for that application.
- R16. The system must support manually written or AI-assisted application and follow-up drafts based on the selected JD and resume facts; it must never send messages automatically.
- R17. The user must review and confirm a communication draft and send it outside the application.

**Interview practice and outcome feedback**

- R18. The first-release interview practice experience must use text questions and text answers related to the target JD and resume.
- R19. A local question bank and self-review must remain available offline; AI-generated questions and feedback are optional enhancements.
- R20. Users must be able to record successful, rejected, and unanswered applications, including reasons when known.
- R21. The system may suggest career-material or practice improvements from outcomes, but must not change the career library without user confirmation.

**AI and offline behavior**

- R22. Core workspace, import, manual tailoring, resume editing, PDF export, application tracking, and local interview practice must work without network access.
- R23. AI features must use an API endpoint and credentials supplied by the user; the product must not bundle a model or provide an AI account.
- R24. An AI request may send only task-relevant data after a user action; contact details must be excluded by default and the data being sent must be shown before the call.
- R25. When no AI endpoint is configured or the endpoint is unreachable, the user must be able to continue through the supported manual or local workflow.

**Web-to-desktop transfer and local data safety**

- R26. The web app must require the user's account password again before exporting a workspace.
- R27. A web export must include the complete user workspace: profile, career materials, JDs, resumes and versions, applications, communication drafts, interviews, and answer assets; it must exclude login credentials and transient task records.
- R28. The export package is not encrypted. The export flow must clearly warn that downloaded contents are readable and must avoid retaining unnecessary server-side temporary copies.
- R29. Desktop import must merge records into the current workspace. Identical records from a repeated import must be skipped; conflicting records must be presented for user resolution.
- R30. The desktop app must provide manual workspace backup and restore without requiring cloud synchronization.
- R31. AI settings must let users add, test, update, disable, or remove an endpoint, credential, and model configuration. Testing a connection must be an explicit user action and must disclose that a test request will be sent to that provider.
- R32. Before every AI task, the app must show the exact task-relevant content to be sent, exclude contact details by default, and require explicit confirmation. Canceling the preview sends nothing.
- R33. AI results must remain drafts until the existing review and acceptance steps are completed. A failed or canceled request must preserve the user's prompt, selected materials, and current draft; it must not create a resume version or alter career materials. The app must not retry automatically or switch providers silently.
- R34. AI credentials are local AI settings, not workspace content. They must not be included in workspace backups, web export packages, or desktop migration packages; after restore or migration the user configures them again.
- R35. Application statuses must be `Preparing`, `Applied`, `Screening`, `Interview`, and `Offer`; terminal outcomes must be `Accepted`, `Rejected`, `Withdrawn`, and `No response`. Each status change must retain its timestamp and optional note. `No response` may be reopened when the employer responds, with prior history retained. The JD and resume version used for an application must not change when later resume versions are created.
- R36. Application reminders must be user-created, one-time local reminders that can be completed, rescheduled, or canceled. They must use Windows notifications at the scheduled time even when the app is closed; if notification delivery is unavailable, the app must show the reminder as overdue on next launch. A reminder must not change application status; closing an application must cancel its pending reminders.
- R37. Workspace import must compare stable record IDs and normalized content. Identical records are skipped; same-ID changed records are conflicts; different IDs are retained as separate records rather than automatically merged based on text similarity.
- R38. Workspace import must validate the package and preview additions, skips, and conflicts before writing. Canceling makes no changes; confirming applies the import atomically. Conflict resolution must offer keeping the local record, replacing it with the imported record, or manually merging fields; each choice preserves the stable ID and downstream references.
- R39. Backup restore is distinct from workspace import: restore replaces the active workspace with the selected snapshot. Before restore, the app must create a safety backup of the current workspace. A canceled, invalid, or failed restore must leave the active workspace unchanged.

### Key Flows

- F1. Resume onboarding
  - **Trigger:** A job seeker opens the desktop app for the first time or chooses to add source material.
  - **Actors:** A1, A4.
  - **Steps:** The user starts an empty workspace or selects a DOCX, text-based PDF, or TXT resume; the app parses candidate profile and career facts locally into a temporary review area; the user can ignore, correct, or confirm each candidate; only confirmed records enter the career library. Canceling, parse failure, and successful completion all clear the temporary source file and unconfirmed parse data.
  - **Outcome:** The user has a verified, reusable career library without requiring AI or network access, and unreviewed source content is not retained.

- F2. Tailor a resume for a target job
  - **Trigger:** The user pastes or imports JD text and starts resume tailoring.
  - **Actors:** A1, A3, A4.
  - **Steps:** The user reviews the JD; the app recommends relevant career materials manually or through an explicitly requested AI call. For an AI call, the user reviews the exact outgoing content and confirms before sending; cancellation sends nothing. The user confirms selected facts and may add truthful missing details; the app drafts a job-specific resume; the user accepts, edits, or rejects content at experience or field level; the app creates a separate resume version.
  - **Outcome:** The user has an editable, traceable resume version for that JD, whether or not AI is configured.
  - **Failure path:** If the provider is offline, rejects credentials, times out, rate-limits, or returns an error, the app explains the failure, retains the prompt, selections, and current draft, and offers retry or manual continuation. No automatic retry or provider switch occurs, and no partial result becomes a saved version.

- F3. Prepare and track an application
  - **Trigger:** The user decides to apply with a tailored resume.
  - **Actors:** A1, A3, A4.
  - **Steps:** The user creates an application linked to the JD and exact resume version, initially in `Preparing`; marks it `Applied` when submitted; and updates it through `Screening`, `Interview`, and `Offer` as applicable. The user can end it as `Accepted`, `Rejected`, `Withdrawn`, or `No response`. Every status change records its time and optional note. The user may set a one-time reminder with a date and time, then complete, reschedule, or cancel it. At the due time the app sends a Windows notification even if closed; if that cannot be delivered, the reminder appears overdue on next launch. The user may request a communication draft, review/edit it, send it outside the application, and manually mark it sent.
  - **Outcome:** The application history identifies the materials used, status changes, outcome, and next user-managed action. Later resume edits do not change the version already linked to the application.
  - **Reopen path:** If an employer responds after `No response`, the user can move the application back to an active status; the prior outcome remains in the history. Ending an application cancels pending reminders, which do not automatically reactivate if the application is reopened.

- F4. Practice and review an interview
  - **Trigger:** The user prepares for an interview or records an application outcome.
  - **Actors:** A1, A3, A4.
  - **Steps:** The user starts a text practice session linked to the relevant application (or keeps it unlinked for general practice), answers local questions, and records self-review. AI-generated questions or feedback follow the same send-preview-confirm and failure-retention rules as F2. Practice completion does not change application status. The user records the application outcome and reason, reviews suggested changes to career facts or practice focus, and confirms any changes to the career library.
  - **Outcome:** Interview practice remains available offline and confirmed feedback can improve future applications.

- F5. Transfer a web workspace to desktop
  - **Trigger:** A signed-in web user chooses to move their work to the desktop app.
  - **Actors:** A1, A2, A4.
  - **Steps:** The web app asks for the account password again; exports a readable, versioned full-workspace package with stable record IDs and a clear sensitivity warning. The package excludes credentials, transient tasks, and the original imported resume file. Desktop validates the package and previews additions, skips, and same-ID conflicts. Identical records are skipped; conflicts show both versions and offer keep-local, use-imported, or manually merge fields. Similar content with different IDs remains separate. The user can cancel with no changes or confirm one atomic merge.
  - **Outcome:** The desktop workspace contains the transferred user data without creating a live sync relationship with the web account.

- F6. Back up and restore a desktop workspace
  - **Trigger:** The user chooses to protect or restore local data.
  - **Actors:** A1, A4.
  - **Steps:** The user creates a local snapshot backup or selects one to restore. Before replacing the active workspace, the app validates the snapshot, explains the replacement scope, and creates a safety backup of the current workspace. The user confirms; restore switches to the selected snapshot only after validation succeeds. Cancel or failure leaves the active workspace unchanged. AI credentials are not restored.
  - **Outcome:** The user can recover a consistent prior workspace without cloud services, while retaining a recovery point for the pre-restore state.

- F7. Configure and use an AI provider
  - **Trigger:** The user opens AI settings or initiates an AI-assisted task.
  - **Actors:** A1, A3, A4.
  - **Steps:** The user enters or edits the endpoint, credential, and model settings and may explicitly test the connection. For each task, the app prepares a task-specific payload and shows it before sending; the user can cancel or confirm. The result remains a draft for the relevant review workflow. On error, the app retains current work and offers retry or manual continuation. The user can disable or remove the configuration at any time.
  - **Outcome:** AI use is deliberate and optional; credentials remain local settings, and provider failure does not block work.

### Acceptance Examples

- AE1. **Covers R22, R25.** Given the desktop app has no network and no AI endpoint, when a user imports a supported resume, corrects the career facts, creates a JD-specific resume manually, and exports it, then the core flow completes locally.
- AE2. **Covers R24.** Given the user requests AI material selection, when the request is prepared, then contact details are absent and the user can inspect the task-relevant content before sending it.
- AE3. **Covers R27, R28.** Given a signed-in web user exports their workspace, when they provide the current account password, then the package includes the specified workspace records, excludes credentials and transient tasks, and is clearly identified as readable after download.
- AE4. **Covers R29.** Given a workspace package is imported twice, when the second import contains identical records and changed conflicting records, then identical records are skipped and the conflicts are presented for user resolution.
- AE5. **Covers R15, R20, R21.** Given an application has an associated JD and resume version, when the user records rejection feedback, then the system preserves the application context and may suggest changes but does not alter source materials until confirmed.
- AE6. **Covers R31-R34.** Given an AI task is prepared, when the user cancels the outgoing-content preview, then no request is sent. When a confirmed request fails with authentication, timeout, rate-limit, or provider error, then the user's prompt, selections, and draft remain available, no version or source fact changes, and the user can retry or continue manually. Workspace backup and migration packages contain no AI credential.
- AE7. **Covers R35-R36.** Given an application moves from `Applied` to `Interview` to `Rejected`, then each transition and timestamp remains visible, the linked JD and resume version remain unchanged, and ending the application cancels its pending reminder. A one-time reminder can be rescheduled or completed without changing application status; it notifies at the due time while the app is closed, or appears overdue at next launch if notification delivery is unavailable.
- AE8. **Covers R37-R38.** Given an import contains identical records, new records, and same-ID changed records, then preview classifies them as skip, add, and conflict respectively. Similar content with different IDs remains separate. Canceling writes nothing; confirming keep-local, use-imported, and manual-merge choices applies the package atomically and preserves references.
- AE9. **Covers R39.** Given the user restores a valid snapshot, then the active workspace becomes that snapshot and a safety backup of the previous workspace exists. If validation fails, the user cancels, or restore fails, the active workspace remains unchanged.

### Scope Boundaries

**Deferred for later**

- Multi-device sync and account binding for desktop workspaces.
- Scanned-PDF OCR, image resumes, and batch import.
- DOCX export and online resume sharing.
- Job-board scraping, email synchronization, contact CRM, and automatic application or message sending.
- Voice-based interview simulation.

### Dependencies and Assumptions

- A user-configured remote AI endpoint requires network access; offline AI is not part of the first release.
- The web export is a portability mechanism, not a cloud backup or synchronization service.
- The user has accepted the trade-off that password re-authentication protects the export action but does not encrypt the downloaded package.
- Existing project documentation describes current behavior and must not override requirements in this contract.

### Outstanding Questions

**Deferred to technical planning**

- Which AI API compatibility protocol and model configuration should the first release support?
- How should local AI credentials, workspace backups, and export temporary files be protected and retained? Credentials must be excluded from all workspace packages regardless of implementation.
- How should the web export format be versioned so later desktop releases can import older packages safely?
- What Windows notification mechanism can satisfy the confirmed reminder behavior when the desktop app is not open, with overdue display on next launch as fallback?
