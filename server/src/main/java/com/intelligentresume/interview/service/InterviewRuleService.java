package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 面试规则降级模式（单事务）。
 *
 * <p>【小白导读】
 * ① 业务问题：模型挂了/一直超时，用户不想干等。点「继续（不用 AI）」后，面试改用
 *    本地写死的规则继续：固定话题轮流出题、按回答长度和关键词机械打分。
 *    这是免费用户的兜底体验，保证「没有模型也能把面试走完、出报告」。
 * ② 链路位置：上游是 {@code InterviewService} 的 continueWithRules / ruleAnswer
 *    （Controller 的 /continue-with-rules 与规则模式下的 /answer）；下游调
 *    {@link InterviewRuleEngine}（纯函数打分出题），写 {@code interview_record} 与会话。
 * ③ 改坏它：降级后还能触发模型调用（授权绕过）、同一答案写出两轮记录（幂等失效）、
 *    或降级是双向的导致 AI 模式回不来。
 *
 * <p>continueWithRules / ruleAnswer 使用方法级 @Transactional（原样迁移），
 * 不混用 TransactionTemplate。
 *
 * <p>术语速查：降级 —— 从「AI 评估」切到「本地规则评分」的单向开关；
 * RULE_FALLBACK —— attempt 的一种终态，表示「这轮由规则接管了」，等同有结论。
 *
 * <p>要点：
 * <ul>
 *   <li>降级是单向的：执行模式一旦置为 {@code RULE} 就再也不会回到 AI；后续轮次全部走规则评分。</li>
 *   <li>降级时把该会话所有 FAILED 的 attempt 改为 {@code RULE_FALLBACK}，表示「这些轮次已由规则接管」，
 *       同时避免前端继续看到可重试的失败。</li>
 *   <li>规则模式不调用模型，因此不需要同意校验，也不占 AI 配额。</li>
 *   <li>幂等靠 attempt 上的 {@code resultJson.ruleCompleted} 标记，重复提交同一答案不会写第二条记录。</li>
 * </ul>
 */
@Service
public class InterviewRuleService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final InterviewRuleEngine ruleEngine;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewRuleService(InterviewSessionRepository sessionRepository,
                                InterviewRecordRepository recordRepository,
                                InterviewAiAttemptRepository attemptRepository,
                                InterviewRuleEngine ruleEngine,
                                InterviewStateAssembler stateAssembler,
                                InterviewOperationSupport operationSupport) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
        this.ruleEngine = ruleEngine;
        this.stateAssembler = stateAssembler;
        this.operationSupport = operationSupport;
    }

    /**
     * 从「AI 不可用」降级到规则模式。
     *
     * <p>只有 {@code AI_ACTION_REQUIRED} 可降级：其他状态说明 AI 正在跑或面试已结束，
     * 此时切换执行模式会与进行中的后台任务冲突。
     */
    @Transactional
    public InterviewStateResponse continueWithRules(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        if (session.getStatus() != InterviewStatus.AI_ACTION_REQUIRED) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许规则降级");
        }

        session.setExecutionMode(ExecutionMode.RULE);
        session.setStatus(InterviewStatus.AWAITING_ANSWER);

        // 标记所有失败的 AI attempt 为 RULE_FALLBACK
        List<InterviewAiAttempt> failedAttempts = attemptRepository.findAllBySessionId(session.getId()).stream()
                .filter(a -> a.getStatus() == AiAttemptStatus.FAILED)
                .toList();
        for (InterviewAiAttempt a : failedAttempts) {
            a.setStatus(AiAttemptStatus.RULE_FALLBACK);
        }
        attemptRepository.saveAll(failedAttempts);

        // 如果当前没有问题（首题失败），给规则首题；已有题目则原样保留，避免把用户看到的题换掉
        if (session.getCurrentQuestion() == null) {
            session.setCurrentQuestion(InterviewRuleEngine.RULE_FIRST_QUESTION);
        }
        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }

    /**
     * 规则模式作答：同步算分、直接写记录并推进到下一题，整个流程在一个事务内完成。
     *
     * <p>幂等分支的终点是「已完成的 attempt」：命中 {@code ruleCompleted} 直接返回当前状态，
     * 不再重复写记录（否则用同一幂等键重放会得到两轮记录，轮次号也会错位）。
     */
    @Transactional
    public InterviewStateResponse ruleAnswer(Long id, String answerText, Long userId, String idempotencyKey) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        // 幂等键命中但内容不同 → 409；同内容且该轮已完成 → 直接回放当前状态
        Optional<InterviewAiAttempt> existing = attemptRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            InterviewAiAttempt previous = existing.get();
            String replayFingerprint = operationSupport.buildFingerprint(id, previous.getRoundNo(), answerText);
            if (!Objects.equals(previous.getSessionId(), id)
                    || previous.getOperationType() != AiAttemptOperationType.ANSWER_EVALUATION
                    || !Objects.equals(previous.getRequestFingerprint(), replayFingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
            }
            if (previous.getStatus() == AiAttemptStatus.RULE_FALLBACK
                    && Boolean.TRUE.equals(previous.getResultJson() != null
                    ? previous.getResultJson().get("ruleCompleted") : null)) {
                return stateAssembler.buildStateResponse(session, null, null);
            }
        }

        if (session.getExecutionMode() != ExecutionMode.RULE) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前不是规则模式");
        }
        if (session.getStatus() != InterviewStatus.AWAITING_ANSWER) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前不在等待回答状态");
        }

        long completedCount = recordRepository.countBySessionId(session.getId());
        int roundNo = (int) completedCount + 1;
        String fingerprint = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);
        Optional<InterviewAiAttempt> roundAttempt = attemptRepository
                .findBySessionIdAndOperationTypeAndRoundNo(session.getId(),
                        AiAttemptOperationType.ANSWER_EVALUATION, roundNo);
        // 三条来源的优先级：幂等键命中 > 本轮已有 attempt（含 AI 失败被降级的那条）> 新建
        InterviewAiAttempt attempt;
        if (existing.isPresent()) {
            attempt = existing.get();
        } else if (roundAttempt.isPresent()) {
            attempt = roundAttempt.get();
            if (attempt.getStatus() != AiAttemptStatus.RULE_FALLBACK
                    || !Objects.equals(attempt.getRequestFingerprint(), fingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前轮次已有不同的回答操作");
            }
            if (Boolean.TRUE.equals(attempt.getResultJson() != null
                    ? attempt.getResultJson().get("ruleCompleted") : null)) {
                return stateAssembler.buildStateResponse(session, null, null);
            }
        } else {
            attempt = operationSupport.createRuleAttempt(userId, session.getId(), roundNo,
                    idempotencyKey, answerText, fingerprint);
        }

        // 规则评分：用一个总分再按固定比例拆成五维，保证五维之和与 roundScore 口径一致。
        // 拆出的每个维度都不超过 AI 契约的上限（25/25/20/20/10），前端可无差别渲染。
        int score = ruleEngine.ruleScore(answerText);
        InterviewRecord record = new InterviewRecord();
        record.setSessionId(session.getId());
        record.setRoundNo(roundNo);
        record.setQuestionText(session.getCurrentQuestion());
        record.setAnswerText(answerText);
        record.setRoundScore(score);
        record.setEvaluationSource(EvaluationSource.RULE);
        record.setAiAttemptId(attempt.getId());
        record.setFeedbackJson(Map.of(
                "dimensionScores", Map.of(
                        "relevance", Math.min(25, score / 4),
                        "evidenceSpecificity", Math.min(25, score / 4),
                        "structureClarity", Math.min(20, score / 5),
                        "roleCompetency", Math.min(20, score / 5),
                        "authenticityReflection", Math.min(10, score / 10)
                ),
                "strengths", score >= 60 ? List.of("回答内容较为完整") : List.of(),
                "improvements", score < 60 ? List.of("建议使用 STAR 原则组织回答，并提供量化结果") : List.of()
        ));
        recordRepository.saveAndFlush(record);

        attempt.setStatus(AiAttemptStatus.RULE_FALLBACK);
        // 复用已有 attempt 时保留原代数（它可能代表此前 AI 失败的次数），新建的则记 0
        attempt.setAttemptCount(existing.isPresent() ? attempt.getAttemptCount() : 0);
        attempt.setPendingAnswer(null);
        attempt.setResultJson(Map.of("ruleCompleted", true, "roundScore", score));
        attemptRepository.save(attempt);

        int newCount = (int) completedCount + 1;

        // 规则模式只按 target 收尾，不参考 min/max 与「信息是否完整」—— 规则模式没有模型判断能力
        if (newCount >= session.getTargetQuestionCount()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.TARGET_REACHED_IN_RULE_MODE);
        } else {
            session.setCurrentQuestion(ruleEngine.nextRuleQuestion(newCount));
        }

        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }
}
