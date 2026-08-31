package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 面试上下文构建与来源校验（无事务边界）。
 *
 * <p>负责首题/评估上下文的组装、简历与 JD 脱敏、来源归属校验。
 */
@Component
public class InterviewPromptContextAssembler {

    private final InterviewContextSanitizer sanitizer;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final InterviewRecordRepository recordRepository;

    public InterviewPromptContextAssembler(InterviewContextSanitizer sanitizer,
                                           JobDescriptionRepository jobDescriptionRepository,
                                           ResumeRepository resumeRepository,
                                           ResumeVersionRepository resumeVersionRepository,
                                           InterviewRecordRepository recordRepository) {
        this.sanitizer = sanitizer;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.recordRepository = recordRepository;
    }

    public String buildFirstQuestionContext(InterviewSession session, Long userId) {
        StringBuilder ctx = new StringBuilder();

        // JD — 加载真实内容
        if (session.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(session.getJobDescriptionId(), userId).ifPresentOrElse(
                    jd -> ctx.append("Job Description:\n").append(sanitizer.truncateJdText(jd.getJdText())).append("\n\n"),
                    () -> ctx.append("Job Description: None (general interview)\n\n")
            );
        } else {
            ctx.append("Job Description: None (general interview)\n\n");
        }

        appendResumeContext(ctx, session, userId);

        ctx.append("Interview Mode: ").append(session.getInterviewMode()).append("\n");

        return ctx.toString();
    }

    public String buildEvaluationContext(InterviewSession session, String answer, Long userId) {
        StringBuilder ctx = new StringBuilder();

        if (session.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(session.getJobDescriptionId(), userId).ifPresent(
                    jd -> ctx.append("Job Description:\n").append(sanitizer.truncateJdText(jd.getJdText())).append("\n\n")
            );
        }

        appendResumeContext(ctx, session, userId);

        long completedCount = recordRepository.countBySessionId(session.getId());
        ctx.append("Interview Progress:\n")
                .append("completedQuestionCount: ").append(completedCount).append('\n')
                .append("minQuestionCount: ").append(session.getMinQuestionCount()).append('\n')
                .append("targetQuestionCount: ").append(session.getTargetQuestionCount()).append('\n')
                .append("maxQuestionCount: ").append(session.getMaxQuestionCount()).append("\n\n");

        // 历史问答
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<Map<String, Object>> recordMaps = new ArrayList<>();
        for (InterviewRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionText", r.getQuestionText());
            m.put("answerText", r.getAnswerText());
            m.put("roundScore", r.getRoundScore());
            m.put("coverageTags", r.getFeedbackJson().getOrDefault("coverageTags", List.of()));
            recordMaps.add(m);
        }

        ctx.append("Conversation History:\n");
        ctx.append(sanitizer.buildHistoryContext(recordMaps));

        ctx.append("\nCurrent Question:\n").append(session.getCurrentQuestion()).append("\n");
        ctx.append("\nCurrent Answer:\n").append(sanitizer.truncateCurrentAnswer(answer)).append("\n");
        ctx.append(sanitizer.untrustedDataMarker());

        return ctx.toString();
    }

    public void appendResumeContext(StringBuilder ctx, InterviewSession session, Long userId) {
        ctx.append("Resume:\n");
        if (session.getSourceType() == InterviewSourceType.PLATFORM_RESUME
                && session.getResumeVersionId() != null) {
            ResumeVersion version = findOwnedResumeVersion(session.getResumeVersionId(), userId);
            Map<String, Object> resumeJson = version.getResumeJson();
            if (resumeJson == null) {
                ctx.append("[empty resume]\n\n");
                return;
            }
            Object summary = sanitizer.sanitizePlatformResume(resumeJson).get("resumeSummary");
            ctx.append(summary != null ? summary.toString() : "[empty resume]").append("\n\n");
        } else if (session.getExternalResumeText() != null) {
            ctx.append(sanitizer.sanitizeExternalResume(session.getExternalResumeText())).append("\n\n");
        } else {
            ctx.append("[empty resume]\n\n");
        }
    }

    public void validateSource(StartInterviewRequest request, Long userId) {
        if (request.sourceType() == InterviewSourceType.PLATFORM_RESUME) {
            if (request.resumeVersionId() == null) throw validation("平台简历来源必须选择简历版本");
            findOwnedResumeVersion(request.resumeVersionId(), userId);
        } else if (request.externalResumeText() == null || request.externalResumeText().isBlank()) {
            throw validation("外部简历来源必须提供简历文本");
        }
        if (request.jobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                    .orElseThrow(() -> notFound("岗位不存在"));
        }
    }

    public ResumeVersion findOwnedResumeVersion(Long versionId, Long userId) {
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> notFound("简历版本不存在"));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> notFound("简历版本不存在"));
        return version;
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
