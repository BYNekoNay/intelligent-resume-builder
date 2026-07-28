package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.*;
import com.intelligentresume.interview.dto.*;
import com.intelligentresume.interview.repository.*;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class InterviewService {
    private static final int MAX_ROUNDS = 3;
    private static final Pattern NUMBER = Pattern.compile("(?i)(\\d+%?|percent|百分之|提升|降低|节省)");
    private static final List<String> STAR_MARKERS = List.of("situation", "task", "action", "result", "背景", "任务", "行动", "结果");
    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeVersionRepository versionRepository;

    public InterviewService(InterviewSessionRepository sessionRepository, InterviewRecordRepository recordRepository,
                            JobDescriptionRepository jobRepository, ResumeVersionRepository versionRepository) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.jobRepository = jobRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional
    public StartInterviewResponse start(StartInterviewRequest request, Long userId) {
        JobDescription job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> notFound("岗位不存在"));
        validateSource(request, userId);
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(request.sourceType());
        session.setResumeVersionId(request.sourceType() == InterviewSourceType.PLATFORM_RESUME ? request.resumeVersionId() : null);
        session.setExternalResumeText(request.sourceType() == InterviewSourceType.EXTERNAL_RESUME ? request.externalResumeText().trim() : null);
        session.setJobDescriptionId(job.getId());
        session.setInterviewMode(request.interviewMode());
        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setCurrentQuestion(firstQuestion(job, request.interviewMode()));
        sessionRepository.saveAndFlush(session);
        return new StartInterviewResponse(session.getId(), session.getCurrentQuestion(), session.getStatus());
    }

    @Transactional
    public AnswerInterviewResponse answer(Long id, AnswerInterviewRequest request, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("Interview session not found"));
        long completedRounds = recordRepository.countBySessionId(session.getId());
        if (session.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试已完成，不能继续作答");
        }
        if (completedRounds >= MAX_ROUNDS) {
            session.setStatus(InterviewStatus.COMPLETED);
            throw new BusinessException(ErrorCode.CONFLICT, "Interview already has the maximum number of rounds");
        }
        JobDescription job = jobRepository.findByIdAndUserId(session.getJobDescriptionId(), userId)
                .orElseThrow(() -> notFound("岗位不存在"));
        String answer = request.answer().trim();
        InterviewFeedback feedback = feedback(answer, job.getJdText());
        InterviewRecord record = new InterviewRecord();
        record.setSessionId(session.getId());
        record.setRoundNo((int) completedRounds + 1);
        record.setQuestionText(session.getCurrentQuestion());
        record.setAnswerText(answer);
        record.setRoundScore(score(answer, job.getJdText()));
        record.setFeedbackJson(Map.of("strengths", feedback.strengths(), "improvements", feedback.improvements()));
        recordRepository.saveAndFlush(record);

        completedRounds++;
        String nextQuestion = completedRounds >= MAX_ROUNDS ? null : nextQuestion(job, session.getInterviewMode(), (int) completedRounds);
        if (nextQuestion == null) session.setStatus(InterviewStatus.COMPLETED);
        else session.setCurrentQuestion(nextQuestion);
        sessionRepository.save(session);
        return new AnswerInterviewResponse(record.getId(), record.getQuestionText(), record.getRoundScore(), feedback, nextQuestion);
    }

    @Transactional(readOnly = true)
    public InterviewReportResponse report(Long id, Long userId) {
        InterviewSession session = owned(id, userId);
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        int total = records.isEmpty() ? 0 : (int) Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));
        List<String> strengths = distinctFeedback(records, "strengths");
        List<String> weaknesses = distinctFeedback(records, "improvements");
        String summary = records.isEmpty() ? "尚未提交回答，暂无可评估内容。"
                : "已完成 %d 轮回答，平均得分 %d 分。%s".formatted(records.size(), total,
                session.getStatus() == InterviewStatus.COMPLETED ? "面试已完成。" : "面试仍在进行中。");
        List<String> resumeSuggestions = weaknesses.stream().anyMatch(v -> v.contains("量化"))
                ? List.of("在简历经历中补充可验证的量化成果，并与回答中的事实保持一致。")
                : List.of("将面试中讲清楚的代表性案例同步沉淀到对应简历经历。" );
        List<String> expressionSuggestions = weaknesses.isEmpty()
                ? List.of("保持结论先行，并继续控制每个案例的篇幅。") : weaknesses;
        return new InterviewReportResponse(total, summary, strengths, weaknesses, resumeSuggestions, expressionSuggestions);
    }

    private void validateSource(StartInterviewRequest request, Long userId) {
        if (request.sourceType() == InterviewSourceType.PLATFORM_RESUME) {
            if (request.resumeVersionId() == null) throw validation("平台简历来源必须选择简历版本");
            ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                    .orElseThrow(() -> notFound("简历版本不存在"));
            if (!userId.equals(version.getCreatedBy()) || version.getDeletedAt() != null) throw notFound("简历版本不存在");
        } else if (request.externalResumeText() == null || request.externalResumeText().isBlank()) {
            throw validation("外部简历来源必须提供简历文本");
        }
    }

    private int score(String answer, String jdText) {
        int score = 35;
        if (answer.length() >= 80) score += 15;
        if (answer.length() >= 160) score += 10;
        String lower = answer.toLowerCase(Locale.ROOT);
        long star = STAR_MARKERS.stream().filter(lower::contains).count();
        score += (int) Math.min(20, star * 5);
        if (NUMBER.matcher(answer).find()) score += 10;
        if (keywordMatches(answer, jdText) >= 2) score += 10;
        return Math.min(100, score);
    }

    private InterviewFeedback feedback(String answer, String jdText) {
        List<String> strengths = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        if (answer.length() >= 80) strengths.add("回答包含了较完整的上下文与行动描述");
        else improvements.add("补充具体背景、个人职责和关键行动");
        if (NUMBER.matcher(answer).find()) strengths.add("使用了量化结果支撑结论");
        else improvements.add("增加可验证的量化结果或业务影响");
        if (keywordMatches(answer, jdText) >= 2) strengths.add("回答与目标岗位的关键要求有明确关联");
        else improvements.add("用真实经历进一步关联岗位要求中的关键技能");
        if (strengths.isEmpty()) strengths.add("回答直面了当前问题");
        return new InterviewFeedback(strengths, improvements);
    }

    private int keywordMatches(String answer, String jdText) {
        String lower = answer.toLowerCase(Locale.ROOT);
        return (int) Arrays.stream(jdText.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#.]+"))
                .filter(token -> token.length() >= 3).distinct().filter(lower::contains).limit(3).count();
    }

    @SuppressWarnings("unchecked")
    private List<String> distinctFeedback(List<InterviewRecord> records, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (InterviewRecord record : records) {
            Object raw = record.getFeedbackJson().get(key);
            if (raw instanceof Collection<?> items) items.stream().filter(String.class::isInstance).map(String.class::cast).forEach(values::add);
        }
        return values.stream().toList();
    }

    private String firstQuestion(JobDescription job, InterviewMode mode) {
        return switch (mode) {
            case TECHNICAL -> "请介绍一个最能体现你技术深度的项目，并说明你在其中的具体贡献。";
            case BEHAVIORAL -> "请用一个具体案例介绍你如何处理有挑战的协作问题。";
            case JD_TARGETED -> "请结合你的经历，说明你为什么适合“" + job.getTitle() + "”岗位。";
            case COMPREHENSIVE -> "请用两分钟介绍你的核心经历，以及它与“" + job.getTitle() + "”岗位的关系。";
        };
    }

    private String nextQuestion(JobDescription job, InterviewMode mode, int completedRounds) {
        if (completedRounds == 1) return "请挑选“" + job.getTitle() + "”岗位的一项核心要求，讲述你解决相关问题的完整过程。";
        return mode == InterviewMode.TECHNICAL
                ? "如果重新设计刚才的方案，你会如何改进技术选型、风险控制和效果验证？"
                : "请描述一次结果不符合预期的经历，以及你复盘后采取了哪些改进。";
    }

    private InterviewSession owned(Long id, Long userId) {
        return sessionRepository.findByIdAndUserId(id, userId).orElseThrow(() -> notFound("面试会话不存在"));
    }
    private BusinessException notFound(String message) { return new BusinessException(ErrorCode.NOT_FOUND, message); }
    private BusinessException validation(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
}
