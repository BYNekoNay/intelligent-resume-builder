package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.dto.InterviewAnswerRequest;
import com.intelligentresume.interview.dto.InterviewResponses;
import com.intelligentresume.interview.dto.InterviewStartRequest;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class InterviewService {

    private final ConsentService consentService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;

    public InterviewService(ConsentService consentService, InterviewSessionRepository sessionRepository,
                            InterviewRecordRepository recordRepository, ResumeVersionRepository versionRepository,
                            ResumeRepository resumeRepository, JobDescriptionRepository jobRepository) {
        this.consentService = consentService;
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public InterviewResponses.Start start(InterviewStartRequest request, Long userId) {
        requireConsent(userId);
        jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        validateResumeSource(request, userId);

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(request.sourceType());
        session.setResumeVersionId(request.resumeVersionId());
        session.setExternalResumeText(request.externalResumeText());
        session.setJobDescriptionId(request.jobDescriptionId());
        session.setInterviewMode(request.interviewMode());
        session.setStatus(InterviewSession.Status.ACTIVE);
        session.setCurrentQuestion(initialQuestion(request.interviewMode()));
        InterviewSession saved = sessionRepository.save(session);
        return new InterviewResponses.Start(saved.getId(), saved.getCurrentQuestion(), saved.getStatus().name());
    }

    @Transactional
    public InterviewResponses.Answer answer(Long id, InterviewAnswerRequest request, Long userId) {
        requireConsent(userId);
        InterviewSession session = owned(id, userId);
        if (session.getStatus() == InterviewSession.Status.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "\u9762\u8bd5\u4f1a\u8bdd\u5df2\u7ed3\u675f");
        }

        List<InterviewRecord> existing = recordRepository.findBySessionIdOrderByCreatedAtAsc(id);
        String question = session.getCurrentQuestion();
        String answer = request.answer().trim();
        int score = Math.min(95, 60 + Math.min(answer.length(), 70) / 2);
        Map<String, List<String>> feedback = Map.of(
                "strengths", List.of("The answer is grounded in the submitted information."),
                "improvements", List.of("Add context, action, and a verifiable outcome."));

        InterviewRecord record = new InterviewRecord();
        record.setSessionId(id);
        record.setQuestionText(question);
        record.setAnswerText(answer);
        record.setRoundScore(score);
        record.setFeedbackJson(Map.of("strengths", feedback.get("strengths"), "improvements", feedback.get("improvements")));
        record = recordRepository.save(record);

        String nextQuestion = existing.size() >= 2 ? null : followUpQuestion(session.getInterviewMode());
        if (nextQuestion == null) {
            session.setStatus(InterviewSession.Status.COMPLETED);
            session.setCurrentQuestion("Interview complete.");
        } else {
            session.setCurrentQuestion(nextQuestion);
        }
        sessionRepository.save(session);
        return new InterviewResponses.Answer(record.getId(), question, score, feedback, nextQuestion);
    }

    public InterviewResponses.Report report(Long id, Long userId) {
        owned(id, userId);
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(id);
        int total = records.isEmpty() ? 0 : (int) Math.round(records.stream()
                .mapToInt(InterviewRecord::getRoundScore).average().orElse(0));
        return new InterviewResponses.Report(total,
                records.isEmpty() ? "No answers submitted yet." : "Completed " + records.size() + " interview rounds.",
                List.of("Answers connect to personal experience."),
                List.of("Strengthen measurable results and technical detail."),
                List.of("Add confirmed project results to the resume."),
                List.of("Use a context-action-result structure."));
    }

    private void validateResumeSource(InterviewStartRequest request, Long userId) {
        if (request.sourceType() == InterviewSession.SourceType.PLATFORM_RESUME) {
            if (request.resumeVersionId() == null) throw new BusinessException(ErrorCode.VALIDATION);
            ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        }
        if (request.sourceType() == InterviewSession.SourceType.EXTERNAL_RESUME
                && (request.externalResumeText() == null || request.externalResumeText().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
    }

    private InterviewSession owned(Long id, Long userId) {
        return sessionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireConsent(Long userId) {
        if (!consentService.isConsented(userId)) throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
    }

    private String initialQuestion(InterviewSession.Mode mode) {
        return switch (mode) {
            case TECHNICAL -> "Describe a technical design decision you made and the tradeoff you considered.";
            case BEHAVIORAL -> "Tell me about a difficult collaboration situation and how you handled it.";
            case JD_TARGETED -> "Which experience best matches this job, and what result did you deliver?";
            case COMPREHENSIVE -> "Describe a project that best demonstrates your fit for this role.";
        };
    }

    private String followUpQuestion(InterviewSession.Mode mode) {
        return switch (mode) {
            case TECHNICAL -> "How did you measure the technical outcome and validate the solution?";
            case BEHAVIORAL -> "What did you learn, and how did you change your approach afterward?";
            case JD_TARGETED -> "Which requirement from this job does that experience prove, and why?";
            case COMPREHENSIVE -> "What was the hardest problem, and what concrete action did you take?";
        };
    }
}
