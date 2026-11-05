package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.service.AiTaskConsentPolicy;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.persistence.AsyncFailureMessages;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 操作生命周期支持：attempt 创建、指纹幂等、配额/同意校验、
 * stale/retry 判定、失败标记、评估结果应用。
 *
 * <p>被 start/answer/retry/rule 流程共享，禁止反向依赖流程类。
 * 日志只记录 id/状态/错误码，不记录简历/JD/回答内容。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code attempt.attemptCount} 一物两用：既是配额计数，也是「第几代重试」。重试开始时 +1，
 *       后台结果落地前用 {@link #isCurrentRetry} 比对代数，代数不符即视为过期结果丢弃。</li>
 *   <li>指纹是 SHA-256，规范化时对每个值写「长度:内容;」，避免拼接歧义（"ab"+"c" 与 "a"+"bc"）。</li>
 *   <li>配额按「今日所有 attempt 的 attemptCount 之和」计算，因此修复调用与重试都会消耗配额。</li>
 *   <li>本类个别方法自带事务（{@link #reserveRepairCall}），其余假定调用方已在事务内并已锁会话行。</li>
 * </ul>
 */
@Component
public class InterviewOperationSupport {

    /**
     * 陈旧接管阈值（秒）：超过它仍未完成的 PROCESSING attempt 才被视为挂死、允许被接管。
     *
     * <p><b>为什么是 600 而不是原来的 75</b>：评估已改为**后台执行**（见
     * {@link InterviewEvaluationConfig}），合法耗时实测 76–146s（4 轮）；
     * 评估自身受 AI 链总预算约束（`app.ai.chain-total-budget=600s`，单模型读超时 300s），
     * 即它**不可能合法地超过 600s** —— 超过必是挂死，接管才是正确的。
     *
     * <p>原值 75 是为旧的同步请求世界设定的：当时 attempt 处于 PROCESSING 期间客户端早已断开，
     * 75s 接管影响不大。异步化后若仍用 75s，轮询中的 `GET /interviews/{id}` 会在评估
     * 正常运行时把它标记为 `PROCESSING_TIMEOUT`，随后后台 TX2 发现状态已变而丢弃结果
     * （实测复现：error_code=CONFLICT「会话状态已变更」）。
     */
    private static final long PROCESSING_TAKEOVER_SECONDS = 600;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final AiProviderRegistry providerRegistry;
    private final InterviewAiService interviewAiService;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;
    private final AiConsentService consentService;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final int interviewDailyQuota;

    public InterviewOperationSupport(InterviewSessionRepository sessionRepository,
                                     InterviewAiAttemptRepository attemptRepository,
                                     AiProviderRegistry providerRegistry,
                                     InterviewAiService interviewAiService,
                                     UserRepository userRepository,
                                     TransactionTemplate tx,
                                     AiConsentService consentService,
                                     InterviewPromptContextAssembler promptContextAssembler,
                                     @Value("${app.ai.quota.INTERVIEW_COACH:60}") int interviewDailyQuota) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.providerRegistry = providerRegistry;
        this.interviewAiService = interviewAiService;
        this.userRepository = userRepository;
        this.tx = tx;
        this.consentService = consentService;
        this.promptContextAssembler = promptContextAssembler;
        this.interviewDailyQuota = interviewDailyQuota;
    }

    public InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> callAiForFirstQuestion(
            InterviewSession session, Long userId) {
        String context = promptContextAssembler.buildFirstQuestionContext(session, userId);
        return interviewAiService.generateFirstQuestion(context, session.getOutputLanguage());
    }

    public String providerRequestId(BusinessException exception) {
        return exception instanceof InterviewAiService.AiInvocationException aiException
                ? aiException.providerRequestId() : null;
    }

    public boolean isRetryable(BusinessException exception) {
        return !(exception instanceof InterviewAiService.AiInvocationException aiException)
                || aiException.retryable();
    }

    /**
     * 判断 PROCESSING 是否已超时（可被接管）。
     *
     * <p>{@code updatedAt} 为空视为「未超时」：没有时间戳时宁可不接管，也不要误杀刚创建的行。
     * 阈值语义见 {@link #PROCESSING_TAKEOVER_SECONDS}，必须大于单次模型调用最坏耗时。
     */
    public boolean isStale(InterviewAiAttempt attempt, LocalDateTime now) {
        return attempt.getUpdatedAt() != null
                && attempt.getUpdatedAt().isBefore(now.minusSeconds(PROCESSING_TAKEOVER_SECONDS));
    }

    /**
     * 判断「这次后台结果是否仍属于当前这一次重试」。
     *
     * <p>四个条件缺一不可：会话仍处于预期状态、仍是 AI 模式（未被降级成规则）、attempt 仍 PROCESSING、
     * 代数（attemptCount）与发起时一致。代数是最关键的一条 —— 用户可能在上一次还在跑时又点了重试，
     * 那时 attemptCount 已变，旧结果必须丢弃，否则会用旧 prompt 的结论覆盖新状态。
     */
    public boolean isCurrentRetry(InterviewSession session, InterviewAiAttempt attempt,
                                  int generation, InterviewStatus expectedStatus) {
        return session.getStatus() == expectedStatus
                && session.getExecutionMode() == ExecutionMode.AI
                && attempt.getStatus() == AiAttemptStatus.PROCESSING
                && Objects.equals(attempt.getAttemptCount(), generation);
    }

    /** {@link #isCurrentRetry} 的断言版本：不满足就抛 CONFLICT，让调用方事务整体回滚。 */
    public void assertRetryStillCurrent(InterviewSession session, InterviewAiAttempt attempt,
                                        int generation, InterviewStatus expectedStatus) {
        if (!isCurrentRetry(session, attempt, generation, expectedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 重试结果已失效");
        }
    }

    /**
     * 校验模型给的「是否继续」与本地题数上限是否自洽。
     *
     * <p>只有一种情况判失败：还没到最大题数、也不满足「已到最低题数且模型认为信息够了」，
     * 却没有给出可用的下一题。这会让面试无法继续，必须当失败处理（可重试）。
     * 注意这里只管「能不能继续」，真正推进状态在 {@link #applyEvaluationOutcome}。
     */
    public void validateEvaluationProgress(InterviewSession session, int completedCount,
                                           InterviewCoachResponse.AnswerEvaluation evaluation,
                                           String providerRequestId) {
        boolean reachesMaximum = completedCount >= session.getMaxQuestionCount();
        boolean mayComplete = completedCount >= session.getMinQuestionCount()
                && evaluation.isInformationComplete();
        if (!reachesMaximum && !mayComplete
                && (evaluation.getNextQuestion() == null
                || evaluation.getNextQuestion().getQuestion() == null
                || evaluation.getNextQuestion().getQuestion().isBlank())) {
            throw new InterviewAiService.AiInvocationException(ErrorCode.AI_FAILURE,
                    "AI 在达到最低题数前未返回下一题", providerRequestId, true);
        }
    }

    /**
     * 根据本轮评估推进会话状态（判序不可调换）。
     *
     * <ol>
     *   <li>先判最大题数：到上限一律结束，模型说「信息还不够」也不听 —— 上限是硬约束。</li>
     *   <li>再判「已达最低题数 且 模型认为信息完整」：提前结束，这是正常收尾。</li>
     *   <li>其余情况进入下一题。调用前必须已通过 {@link #validateEvaluationProgress}，
     *       否则这里可能 NPE（nextQuestion 为 null）。</li>
     * </ol>
     */
    public void applyEvaluationOutcome(InterviewSession session, int completedCount,
                                       InterviewCoachResponse.AnswerEvaluation evaluation) {
        if (completedCount >= session.getMaxQuestionCount()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.MAX_QUESTION_LIMIT);
        } else if (completedCount >= session.getMinQuestionCount()
                && evaluation.isInformationComplete()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.AI_INFORMATION_COMPLETE);
        } else {
            session.setCurrentQuestion(evaluation.getNextQuestion().getQuestion());
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
        }
    }

    /**
     * 把评估结果拍平成 {@code interview_record.feedback_json}。
     *
     * <p>key 名是持久化契约：状态接口（LastEvaluation）、报告聚合、资产导出都按这些 key 回读。
     * 这里的可空列表统一落空数组，保证读取侧不需要再判 null。
     */
    public Map<String, Object> buildAiFeedback(InterviewCoachResponse.AnswerEvaluation evaluation) {
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("dimensionScores", Map.of(
                "relevance", evaluation.getDimensionScores().getRelevance(),
                "evidenceSpecificity", evaluation.getDimensionScores().getEvidenceSpecificity(),
                "structureClarity", evaluation.getDimensionScores().getStructureClarity(),
                "roleCompetency", evaluation.getDimensionScores().getRoleCompetency(),
                "authenticityReflection", evaluation.getDimensionScores().getAuthenticityReflection()
        ));
        feedback.put("strengths", evaluation.getStrengths());
        feedback.put("improvements", evaluation.getImprovements());
        feedback.put("suggestedAnswer", evaluation.getSuggestedAnswer());
        feedback.put("resumeSuggestions", evaluation.getResumeSuggestions() != null
                ? evaluation.getResumeSuggestions() : List.of());
        feedback.put("expressionSuggestions", evaluation.getExpressionSuggestions() != null
                ? evaluation.getExpressionSuggestions() : List.of());
        feedback.put("evidenceQuotes", evaluation.getEvidenceQuotes() != null
                ? evaluation.getEvidenceQuotes() : List.of());
        feedback.put("coverageTags", evaluation.getCoverageTags() != null
                ? evaluation.getCoverageTags() : List.of());
        return feedback;
    }

    /**
     * 统一失败落库：会话置 {@code AI_ACTION_REQUIRED}，attempt 置 FAILED 并写入错误码/可重试标记。
     *
     * <p>错误文案经 {@code AsyncFailureMessages.persisted(...)} 处理，因为该字段要存库且可能被回显，
     * 不能直接放 provider 原文。调用方必须已持有会话行锁并负责提交事务。
     */
    public void markAttemptFailed(InterviewSession session, InterviewAiAttempt attempt,
                                  String errorCode, String errorMessage, boolean retryable,
                                  String providerRequestId) {
        session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
        sessionRepository.save(session);
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setErrorCode(errorCode);
        attempt.setErrorMessage(AsyncFailureMessages.persisted(errorMessage));
        attempt.setRetryable(retryable);
        attempt.setProviderRequestId(providerRequestId);
        attemptRepository.save(attempt);
    }

    /**
     * 创建一次 AI attempt，初始即 PROCESSING、代数 1。
     *
     * <p>同时快照 provider/model/prompt 版本，用于事后判断「当时的模型与提示词」。
     * {@code user_id / session_id / operation_type / round_no} 与唯一约束共同决定了同一轮同一操作只能有一条。
     */
    public InterviewAiAttempt createAttempt(Long userId, Long sessionId,
                                            AiAttemptOperationType opType, Integer roundNo,
                                            String idempotencyKey, String pendingAnswer,
                                            String fingerprint) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUserId(userId);
        attempt.setSessionId(sessionId);
        attempt.setOperationType(opType);
        attempt.setRoundNo(roundNo);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setPendingAnswer(pendingAnswer);
        attempt.setAttemptCount(1);

        var provider = providerRegistry.route(AiTaskType.INTERVIEW_COACH);
        attempt.setProviderCode(provider.code());
        attempt.setModelCode(provider.modelCode());
        attempt.setPromptVersion(interviewAiService.promptVersion());

        return attemptRepository.save(attempt);
    }

    /**
     * 规则模式的 attempt 占位记录。
     *
     * <p>与 AI attempt 的差异是刻意的：代数记 0（不占配额）、没有 provider/model 快照、
     * promptVersion 固定为 {@code rule-v1}。这样统计配额时规则评分不会被计入。
     */
    public InterviewAiAttempt createRuleAttempt(Long userId, Long sessionId, Integer roundNo,
                                                String idempotencyKey, String answer,
                                                String fingerprint) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUserId(userId);
        attempt.setSessionId(sessionId);
        attempt.setOperationType(AiAttemptOperationType.ANSWER_EVALUATION);
        attempt.setRoundNo(roundNo);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setPendingAnswer(answer);
        attempt.setAttemptCount(0);
        attempt.setPromptVersion("rule-v1");
        return attemptRepository.save(attempt);
    }

    /** 回答评估的请求指纹：会话 + 轮次 + 答案。三者任一变化都视为另一个请求。 */
    public String buildFingerprint(Long sessionId, Integer roundNo, String answer) {
        return secureFingerprint(sessionId, roundNo, answer);
    }

    /**
     * 开场请求指纹。所有会影响首题内容的入参都要列进来，否则「改了 JD 但用同一幂等键」
     * 会被误判为重放。注意这里用的是 request 原值（未夹逼的 targetQuestionCount 等）。
     */
    public String buildStartFingerprint(Long userId, StartInterviewRequest request) {
        return secureFingerprint("start", userId,
                request.sourceType(), request.resumeVersionId(),
                request.externalResumeText(), request.jobDescriptionId(), request.interviewMode(),
                request.targetQuestionCount(),
                request.outputLanguage() != null ? request.outputLanguage() : InterviewOutputLanguage.ZH_CN);
    }

    /**
     * 计算 SHA-256 指纹。
     *
     * <p>规范化格式为 {@code len:value;}：长度前缀消除拼接歧义，null 统一写成 {@code <null>}，
     * 这样不同入参组合不会算出同一个摘要。指纹列宽 128，hex 摘要为 64 字符，安全。
     */
    public String secureFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "<null>" : value.toString();
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 面试每日配额校验。
     *
     * <p>为什么要锁用户行：并发提交时若不加锁，多个请求会同时读到「未超额」而全部放行。
     * 计数口径是 {@code sum(attemptCount)}（今天创建的所有 attempt），所以一次评估的契约修复
     * 与一次手动重试都会各自计 1 次。
     */
    public void checkInterviewQuota(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("用户不存在"));
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long count = attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(userId, startOfToday);
        if (count >= interviewDailyQuota) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 面试配额已用完（每日 " + interviewDailyQuota + " 次）");
        }
    }

    /**
     * 为「契约修复」这次额外调用预占配额并把 attempt 代数 +1。
     *
     * <p>必须是独立事务：由 {@code InterviewAiService} 在真正发起第二次模型调用前回调，
     * 若这里抛异常（配额不足/attempt 已失效）修复调用就不会发生。
     * 代数 +1 使重试流程能识别出「这次结果属于第几代」，详见 {@link #isCurrentRetry}。
     */
    public void reserveRepairCall(Long userId, Long attemptId) {
        tx.executeWithoutResult(status -> {
            checkInterviewQuota(userId);
            InterviewAiAttempt attempt = attemptRepository.findById(attemptId)
                    .orElseThrow(() -> notFound("AI 操作不存在"));
            if (attempt.getStatus() != AiAttemptStatus.PROCESSING) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI 操作已失效");
            }
            attempt.setAttemptCount(attempt.getAttemptCount() + 1);
            attemptRepository.save(attempt);
        });
    }

    /**
     * 同意校验：数据类别由 {@code AiTaskConsentPolicy} 按任务类型 + 快照（是否带 JD）推导，
     * 与通用 AI 任务链路共用同一份策略，避免两处判断不一致。
     *
     * <p>快照只放 {@code jobDescriptionId}：带了 JD 就会多出 JD 这一数据类别，需要额外授权。
     */
    public boolean hasInterviewConsent(Long userId, InterviewSession session) {
        Map<String, Object> snapshot = session.getJobDescriptionId() == null
                ? Map.of()
                : Map.of("jobDescriptionId", session.getJobDescriptionId());
        List<String> categories = AiTaskConsentPolicy.requiredCategories(AiTaskType.INTERVIEW_COACH, snapshot);
        return consentService.hasValidConsent(userId, "INTERVIEW_COACH", categories);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}
