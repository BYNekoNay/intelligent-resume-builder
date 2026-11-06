package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试响应只读组装（无事务边界，由调用方在事务上下文中执行）。
 *
 * <p>【小白导读】
 * ① 业务问题：前端需要知道「面试现在什么状态、当前题目、上轮点评、失败原因」。
 *    这里把会话、最近一轮记录、失败 attempt 三样东西拼成一个统一的状态响应，
 *    是所有面试接口返回给前端的那张「仪表盘」。
 * ② 链路位置：被 start/answer/retry/rule/getState/report 各流程调用（几乎每个接口的
 *    返回值都出自这里）；只读数据库（会话/记录/attempt 三表），不写库、不调模型。
 * ③ 改坏它：轮询接口变慢（误读全量记录）、前端进度条错乱（已完成轮数口径不一致）、
 *    或失败时用户看到的报错提示驴唇不对马嘴。
 *
 * <p>负责 buildStateResponse、LastEvaluation、AiFailureInfo、
 * 失败 attempt 查询与反馈去重等纯组装逻辑。
 *
 * <p>要点：
 * <ul>
 *   <li>本类不写库、不开事务；调用方需保证传入的 session 处于已加锁/已加载的上下文。</li>
 *   <li>状态组装只读最新一轮记录，不做全量加载 —— buildStateResponse 是前端轮询热路径。</li>
 *   <li>「已完成轮数」的唯一口径是 {@code interview_record} 计数，不是会话字段。</li>
 *   <li>{@code AiFailureInfo.messageCode} 用错误码而非错误文案：文案含 provider 信息，不下发。</li>
 * </ul>
 */
@Component
public class InterviewStateAssembler {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;

    public InterviewStateAssembler(InterviewSessionRepository sessionRepository,
                                   InterviewRecordRepository recordRepository,
                                   InterviewAiAttemptRepository attemptRepository) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
    }

    /**
     * 组装统一状态响应。
     *
     * <p>{@code currentQuestionNo} 目前只在「已有完成轮次」时才给值（= 已完成数 + 1），
     * 首题阶段为 null，前端按 null 处理。
     * {@code lastEval} 传 null 时自动从最新一轮记录回填，因此调用方一般无需自己查。
     */
    public InterviewStateResponse buildStateResponse(InterviewSession session,
                                                     InterviewStateResponse.LastEvaluation lastEval,
                                                     InterviewStateResponse.AiFailureInfo aiFailure) {
        long count = recordRepository.countBySessionId(session.getId());
        Integer currentQNo = count > 0 ? (int) count + 1 : null;

        // 自动加载最近一轮评估（#43：只取末条——此前全量读取本会话所有轮次再取 `size()-1`，
        // 而这是前端 1/2/4/5s 轮询的热路径：实测 9 轮会话每次轮询向 DB 取 99.6KB（整份答案与
        // 评估 JSON），改后 13.6KB 且与已完成轮数无关）
        if (lastEval == null && count > 0) {
            InterviewRecord latest = recordRepository
                    .findFirstBySessionIdOrderByRoundNoDescIdAsc(session.getId())
                    .orElse(null);
            if (latest != null) {
                lastEval = buildLastEvaluation(latest);
            }
        }

        return new InterviewStateResponse(
                session.getId(), session.getStatus(), session.getExecutionMode(),
                session.getCurrentQuestion(), currentQNo,
                (int) count, session.getTargetQuestionCount(),
                session.getMinQuestionCount(), session.getMaxQuestionCount(),
                lastEval, aiFailure, session.getCompletionReason(),
                session.getSourceType(), session.getInterviewMode(), session.getOutputLanguage(),
                session.getResumeVersionId(), session.getJobDescriptionId()
        );
    }

    /** 把一条记录转成「最近一次评估」视图；feedbackJson 里缺字段时对应 DTO 字段保持默认值。 */
    @SuppressWarnings("unchecked")
    public InterviewStateResponse.LastEvaluation buildLastEvaluation(InterviewRecord record) {
        InterviewStateResponse.LastEvaluation eval = new InterviewStateResponse.LastEvaluation();
        eval.setRecordId(record.getId());
        eval.setRoundNo(record.getRoundNo());
        eval.setQuestionText(record.getQuestionText());
        eval.setAnswerText(record.getAnswerText());
        eval.setRoundScore(record.getRoundScore());
        eval.setEvaluationSource(record.getEvaluationSource());

        Map<String, Object> feedback = record.getFeedbackJson();
        if (feedback != null) {
            Object dims = feedback.get("dimensionScores");
            if (dims instanceof Map<?, ?> m) {
                InterviewStateResponse.DimensionScores ds = new InterviewStateResponse.DimensionScores();
                ds.setRelevance(num(m.get("relevance")));
                ds.setEvidenceSpecificity(num(m.get("evidenceSpecificity")));
                ds.setStructureClarity(num(m.get("structureClarity")));
                ds.setRoleCompetency(num(m.get("roleCompetency")));
                ds.setAuthenticityReflection(num(m.get("authenticityReflection")));
                eval.setDimensionScores(ds);
            }
            Object strengths = feedback.get("strengths");
            if (strengths instanceof List<?> list) {
                eval.setStrengths(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
            }
            Object improvements = feedback.get("improvements");
            if (improvements instanceof List<?> list) {
                eval.setImprovements(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
            }
            Object suggested = feedback.get("suggestedAnswer");
            if (suggested instanceof String s) {
                eval.setSuggestedAnswer(s);
            }
        }
        return eval;
    }

    /**
     * 由 attempt 组装失败信息。
     *
     * <p>{@code reauthorizationRequired} 只认 {@code FORBIDDEN}：这类失败重试也没用，
     * 必须先补授权；其他错误码一律按「可能重试」处理，具体看 attempt 上的 retryable。
     */
    public InterviewStateResponse.AiFailureInfo buildAiFailure(InterviewAiAttempt attempt) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(attempt.getId());
        info.setStage(attempt.getOperationType().name());
        info.setRetryable(Boolean.TRUE.equals(attempt.getRetryable()));
        info.setReauthorizationRequired("FORBIDDEN".equals(attempt.getErrorCode()));
        info.setMessageCode(attempt.getErrorCode() != null ? attempt.getErrorCode() : ErrorCode.AI_FAILURE.name());
        return info;
    }

    /**
     * 由异常现场组装失败信息（尚未落库时使用）。
     *
     * <p>与 {@link #buildAiFailure(InterviewAiAttempt)} 的区别：可重试性取「不是 FORBIDDEN」，
     * 因为此时还没有 attempt 上人工判定的 retryable。
     */
    public InterviewStateResponse.AiFailureInfo buildAiFailure(Long operationId, String stage, BusinessException e) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(operationId);
        info.setStage(stage);
        info.setRetryable(e.getErrorCode() != ErrorCode.FORBIDDEN);
        info.setReauthorizationRequired(e.getErrorCode() == ErrorCode.FORBIDDEN);
        info.setMessageCode(e.getErrorCode().name());
        return info;
    }

    /** 最近一条失败 attempt（按 updatedAt + id 双键降序，避免同秒并列时取错）。 */
    public Optional<InterviewAiAttempt> latestFailedAttempt(Long sessionId) {
        return attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc(
                sessionId, com.intelligentresume.interview.domain.AiAttemptStatus.FAILED);
    }

    /**
     * 跨轮次去重收集某类反馈（strengths / improvements / resumeSuggestions / expressionSuggestions）。
     *
     * <p>用 LinkedHashSet 保序去重（先出现的优先），最终只取前 5 条：报告页要的是「高频共性问题」，
     * 全量罗列反而看不出重点。key 不存在或值不是集合时静默跳过。
     */
    @SuppressWarnings("unchecked")
    public List<String> distinctFeedback(List<InterviewRecord> records, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (InterviewRecord record : records) {
            Object raw = record.getFeedbackJson().get(key);
            if (raw instanceof Collection<?> items) {
                items.stream().filter(String.class::isInstance).map(String.class::cast).forEach(values::add);
            }
        }
        return values.stream().limit(5).toList();
    }

    /**
     * 宽松数值读取：非 Number（含 null、字符串）一律当 0。
     *
     * <p>用于读历史 feedbackJson —— 早期数据的形状不完全一致，宁可显示 0 也不要因为一条脏数据
     * 让整个状态接口 500。
     */
    public int num(Object val) {
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    /**
     * 读取属于该用户的会话（<b>不加锁</b>）。只读场景用这个；
     * 需要写或需要状态不被并发修改时必须换成 {@code findByIdAndUserIdForUpdate}。
     */
    public InterviewSession owned(Long id, Long userId) {
        return sessionRepository.findByIdAndUserId(id, userId).orElseThrow(() -> notFound("面试会话不存在"));
    }

    public BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
