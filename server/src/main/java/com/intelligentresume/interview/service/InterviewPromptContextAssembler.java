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
 * <p>【小白导读】
 * ① 业务问题：模型出题/点评前需要「开卷材料」——JD、简历摘要、面试进度、历史问答。
 *    这里把这些材料拼成一段模型能读懂的文本，并顺手校验「用户选的简历/JD 是不是他自己的」。
 * ② 链路位置：被 start（首题上下文）、answer/retry（评估上下文）、follow-up（预览+执行）
 *    调用；下游读 JD/简历/面试记录三张表，产出纯文本（String）交给 {@link InterviewAiService}
 *    拼 prompt。自己不调模型、不写库。
 * ③ 改坏它：把别人的简历拼进 prompt（越权）、敏感信息（邮箱/电话）漏进模型、
 *    或进度数字与库里对不上导致模型收尾判断错乱。
 *
 * <p>负责首题/评估上下文的组装、简历与 JD 脱敏、来源归属校验。
 *
 * <p>要点：
 * <ul>
 *   <li>只读：不写库、不开事务。因此也能被「发送前预览」复用（预览要求零副作用）。</li>
 *   <li>上下文里所有用户内容都经 {@link InterviewContextSanitizer}；评估上下文末尾必须带上
 *       不可信数据标记，首题上下文则把数据包在模板的 {@code [UNTRUSTED_USER_DATA]} 块里。</li>
 *   <li>找 JD / 简历时一律带 userId 过滤，越权与不存在返回同一种 404 语义（不区分）。</li>
 *   <li>评估上下文包含进度与历史：{@code completedQuestionCount} 是模型判断「能不能收尾」的主要依据，
 *       必须与库里记录数一致。</li>
 * </ul>
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

    /**
     * 首题上下文：JD + 简历 + 面试模式。
     *
     * <p>与评估上下文不同，这里<b>没有</b>历史问答和不可信标记 —— 历史为空，标记由模型模板
     * （{@code FIRST_QUESTION_TEMPLATE}）统一包裹，重复添加只会浪费 token。
     * JD 取不到时显式写 {@code None (general interview)}，让模型按通用岗位标准出题而不是凭空假设。
     */
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

    /**
     * 评估上下文：JD + 简历 + 进度 + 历史问答 + 当前题目 + 当前回答。
     *
     * <p>进度（completed / min / target / max）必须和 {@code InterviewSession} 上的值一致，
     * 这是模型决定「给不给下一题、能不能收尾」的唯一依据；本地还会用 {@code validateEvaluationProgress}
     * 再兜一层，两侧口径对不上就会频繁触发重试。
     *
     * <p>本方法会读全量历史记录（构建上下文需要），与状态接口的「只读末条」是两种取舍：
     * 这里每轮只调用一次，状态接口每秒可能被轮询多次。
     */
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
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByRoundNoAscIdAsc(session.getId());
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

    /**
     * 按来源类型写入简历段落。三个分支各自写入内容或一段明确占位，
     * 避免模型对着空的 "Resume:" 自行编造经历（外部简历的非空由 {@link #validateSource} 保证）。
     */
    public void appendResumeContext(StringBuilder ctx, InterviewSession session, Long userId) {
        ctx.append("Resume:\n");
        if (session.getSourceType() == InterviewSourceType.PLATFORM_RESUME) {
            if (session.getResumeVersionId() == null) {
                ctx.append("[empty resume]\n\n");
                return;
            }
            ResumeVersion version = findOwnedResumeVersion(session.getResumeVersionId(), userId);
            Map<String, Object> resumeJson = version.getResumeJson();
            if (resumeJson == null) {
                ctx.append("[empty resume]\n\n");
                return;
            }
            Object summary = sanitizer.sanitizePlatformResume(resumeJson).get("resumeSummary");
            ctx.append(summary != null ? summary.toString() : "[empty resume]").append("\n\n");
        } else if (session.getSourceType() == InterviewSourceType.EXTERNAL_RESUME
                && session.getExternalResumeText() != null) {
            ctx.append(sanitizer.sanitizeExternalResume(session.getExternalResumeText())).append("\n\n");
        } else {
            ctx.append("[unsupported or empty resume source]\n\n");
        }
    }

    /**
     * 开场前的来源校验：决定「请求里哪些字段是必填的」。
     *
     * <p>平台简历必须有版本且属于本人（可能已归档，归档由 {@link #findOwnedResumeVersion} 拦）；
     * 外部简历必须有正文；其他来源类型直接拒绝。JD 可选，但给了就必须属于本人。
     */
    public void validateSource(StartInterviewRequest request, Long userId) {
        if (request.sourceType() == InterviewSourceType.PLATFORM_RESUME) {
            if (request.resumeVersionId() == null) throw validation("平台简历来源必须选择简历版本");
            findOwnedResumeVersion(request.resumeVersionId(), userId);
        } else if (request.sourceType() == InterviewSourceType.EXTERNAL_RESUME) {
            if (request.externalResumeText() == null || request.externalResumeText().isBlank()) {
                throw validation("外部简历来源必须提供简历文本");
            }
        } else {
            throw validation("不支持的面试简历来源");
        }
        if (request.jobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                    .orElseThrow(() -> notFound("岗位不存在"));
        }
    }

    /**
     * 读取属于本人的简历版本。三步判定的<b>顺序是安全要求</b>：
     * 归属校验必须早于归档判定，否则「版本不存在（他人）」与「版本已归档（本人）」会给出
     * 不同响应码，等于把别人的版本号是否存在探测出来。
     *
     * <p>归档是可逆状态，因此用 {@code VERSION_ARCHIVED}(40902) + 「请先恢复」文案，
     * 而不是 NOT_FOUND —— 后者给出的建议（重新上传）在这里是错的。
     */
    public ResumeVersion findOwnedResumeVersion(Long versionId, Long userId) {
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .orElseThrow(() -> notFound("简历版本不存在"));
        // 归属校验放在归档判定之前：不通过时一律以 404 收口，避免用归档状态反推他人版本是否存在。
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> notFound("简历版本不存在"));
        if (version.getDeletedAt() != null) {
            // 归档是可逆状态：与 ATS/评分/导出/投递一致用专属码 40902 +「先恢复」文案。
            // 面试会话（含进行中每轮评估）引用到已归档版本时，用户看到的应是「请先恢复」，
            // 而不是原因错误且恢复也无法消除的「简历版本不存在」。
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再继续");
        }
        return version;
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
