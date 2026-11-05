package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.persistence.AsyncFailureMessages;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 面试开始流程：两阶段短事务 + 事务外首题 AI。
 *
 * <p>TX1 创建会话并校验同意/配额/幂等 → 事务外调用首题 AI → TX2 落库。
 *
 * <p>要点：
 * <ul>
 *   <li>AI 调用绝不能放进事务：单次模型调用可达百秒级，会长时间占用连接与行锁。</li>
 *   <li>TX2 必须重新加锁并校验状态仍是 {@code GENERATING_QUESTION}，否则说明期间被别的操作
 *       （陈旧接管、用户降级规则模式）改过，此时只能抛 CONFLICT 丢弃本次结果。</li>
 *   <li>题数被夹在 [{@value #MIN_TARGET}, {@value #MAX_TARGET}]；min/max 由 target 派生，
 *       三者的含义见 {@link InterviewSession}。改派生公式会同时影响完成判定。</li>
 *   <li>幂等：同一 Idempotency-Key + 同一请求指纹 → 直接回放已有会话；键相同但指纹不同 → 409。</li>
 *   <li>调用方显式传入 {@code initialQuestion} 时不调 AI，直接把它当作首题（练习/重放场景）。</li>
 * </ul>
 */
@Service
public class InterviewStartService {

    private static final int DEFAULT_TARGET = 6;
    private static final int MIN_TARGET = 4;
    private static final int MAX_TARGET = 12;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewStartService(InterviewSessionRepository sessionRepository,
                                 InterviewAiAttemptRepository attemptRepository,
                                 TransactionTemplate tx,
                                 InterviewStateAssembler stateAssembler,
                                 InterviewPromptContextAssembler promptContextAssembler,
                                 InterviewOperationSupport operationSupport) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.tx = tx;
        this.stateAssembler = stateAssembler;
        this.promptContextAssembler = promptContextAssembler;
        this.operationSupport = operationSupport;
    }

    private record StartPreparation(Long sessionId, boolean replayed, Long attemptId) {}

    public InterviewStateResponse start(StartInterviewRequest request, Long userId, String idempotencyKey) {
        // 计算题数：先夹逼用户输入，再由 target 派生出「至少 / 至多」轮数
        // （min = target×50% 向上取整，max = target×150% 向下取整）。
        int target = request.targetQuestionCount() != null
                ? Math.max(MIN_TARGET, Math.min(MAX_TARGET, request.targetQuestionCount()))
                : DEFAULT_TARGET;
        int minQ = (int) Math.ceil(target * 0.5);
        int maxQ = (int) Math.floor(target * 1.5);
        InterviewOutputLanguage outputLanguage = request.outputLanguage() != null
                ? request.outputLanguage() : InterviewOutputLanguage.ZH_CN;
        // 首题允许调用方指定；全空白视为未指定（否则会生成一个空字符串题目）
        String initialQuestion = request.initialQuestion() == null ? null : request.initialQuestion().trim();
        if (initialQuestion != null && initialQuestion.isBlank()) {
            initialQuestion = null;
        }

        // 验证来源：平台简历必须属于本人且未归档；外部简历必须有正文；JD 必须属于本人
        promptContextAssembler.validateSource(request, userId);

        // 构建请求指纹：幂等键相同但入参不同时必须能识别出来（否则会把 A 请求的结果返回给 B）
        String fingerprint = operationSupport.buildStartFingerprint(userId, request);

        // TX1: 创建会话 + 校验同意/配额/幂等
        StartPreparation preparation = tx.execute(status -> {
            // 幂等检查：相同键 + 相同指纹 → 返回已有结果
            Optional<InterviewAiAttempt> existing = attemptRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                InterviewAiAttempt prev = existing.get();
                if (prev.getOperationType() != AiAttemptOperationType.INITIAL_QUESTION
                        || !Objects.equals(prev.getRequestFingerprint(), fingerprint)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
                }
                InterviewSession previousSession = sessionRepository
                        .findByIdAndUserIdForUpdate(prev.getSessionId(), userId).orElseThrow();
                if (prev.getStatus() == AiAttemptStatus.PROCESSING
                        && operationSupport.isStale(prev, LocalDateTime.now())) {
                    // 上次请求的进程已死：把旧 attempt 判死并继续返回旧会话状态，让用户点重试
                    operationSupport.markAttemptFailed(previousSession, prev, "PROCESSING_TIMEOUT",
                            "AI 请求处理超时，请重试", true, null);
                }
                return new StartPreparation(previousSession.getId(), true, prev.getId());
            }

            // 先落库拿到 sessionId，attempt 才能引用它；状态直接置为「生成中」，避免中间态被读取
            InterviewSession session = new InterviewSession();
            session.setUserId(userId);
            session.setSourceType(request.sourceType());
            // 两种来源只保留各自相关字段，另一侧写 null，避免同一会话出现两套简历数据
            session.setResumeVersionId(request.sourceType() == InterviewSourceType.PLATFORM_RESUME ? request.resumeVersionId() : null);
            session.setExternalResumeText(request.sourceType() == InterviewSourceType.EXTERNAL_RESUME ? request.externalResumeText().trim() : null);
            session.setJobDescriptionId(request.jobDescriptionId());
            session.setInterviewMode(request.interviewMode());
            session.setOutputLanguage(outputLanguage);
            session.setStatus(InterviewStatus.GENERATING_QUESTION);
            session.setCurrentQuestion(null);
            session.setExecutionMode(ExecutionMode.AI);
            session.setTargetQuestionCount(target);
            session.setMinQuestionCount(minQ);
            session.setMaxQuestionCount(maxQ);
            sessionRepository.saveAndFlush(session);

            // 校验同意：缺同意也落一条 FAILED attempt（errorCode=FORBIDDEN、retryable=false），
            // 这样前端拿到的是结构化的 aiFailure，而不是一个无名错误；授权后续可在 /ai/retry 里恢复
            if (!operationSupport.hasInterviewConsent(userId, session)) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                        AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("FORBIDDEN");
                attempt.setErrorMessage("需要 AI 面试授权，请先同意隐私政策");
                attempt.setRetryable(false);
                attemptRepository.save(attempt);
                return new StartPreparation(session.getId(), false, attempt.getId());
            }

            // 校验配额：与同意不同，配额属于「稍后可能恢复」，所以 retryable=true
            try {
                operationSupport.checkInterviewQuota(userId);
            } catch (BusinessException e) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                        AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("RATE_LIMITED");
                attempt.setErrorMessage(AsyncFailureMessages.persisted(e.getMessage()));
                attempt.setRetryable(true);
                attemptRepository.save(attempt);
                return new StartPreparation(session.getId(), false, attempt.getId());
            }

            InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                    AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);

            return new StartPreparation(session.getId(), false, attempt.getId());
        });

        Long sessionId = preparation.sessionId();
        // 幂等回放：不重复建会话、不调 AI，只把上次的终态（含失败信息）再返回一次
        if (preparation.replayed()) {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            return stateAssembler.buildStateResponse(session, null,
                    attempt.getStatus() == AiAttemptStatus.FAILED ? stateAssembler.buildAiFailure(attempt) : null);
        }

        // TX1 后检查：TX1 内已因同意/配额失败时，会话已是 AI_ACTION_REQUIRED，不能再调 AI。
        // 这里用最近一条 FAILED（id 降序）而不是最新更新，保证拿到的是本次刚写的那条。
        InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
        if (afterTx1.getStatus() == InterviewStatus.AI_ACTION_REQUIRED) {
            InterviewAiAttempt failedAttempt = attemptRepository
                    .findTopBySessionIdAndStatusOrderByIdDesc(sessionId, AiAttemptStatus.FAILED).orElse(null);
            return stateAssembler.buildStateResponse(afterTx1, null,
                    failedAttempt != null ? stateAssembler.buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION",
                            new BusinessException(
                                    "FORBIDDEN".equals(failedAttempt.getErrorCode()) ? ErrorCode.FORBIDDEN : ErrorCode.RATE_LIMITED,
                                    failedAttempt.getErrorMessage())) : null);
        }

        // 提供 initialQuestion 时跳过 AI 首题生成，会话直接进入 AWAITING_ANSWER
        // attempt 仍记为 SUCCESS 并写入题目，使该会话同样具备一条可追溯的首题记录（幂等回放依赖它）
        if (initialQuestion != null) {
            String practiceQuestion = initialQuestion;
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                if (session.getStatus() != InterviewStatus.GENERATING_QUESTION) {
                    throw new BusinessException(ErrorCode.CONFLICT, "会话状态已变更");
                }
                session.setCurrentQuestion(practiceQuestion);
                session.setStatus(InterviewStatus.AWAITING_ANSWER);
                sessionRepository.save(session);

                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                attempt.setStatus(AiAttemptStatus.SUCCESS);
                attempt.setResultJson(Map.of("question", practiceQuestion));
                attempt.setPendingAnswer(null);
                attemptRepository.save(attempt);
            });
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            return stateAssembler.buildStateResponse(session, null, null);
        }

        // Phase 2: 事务外调用 AI（此处无事务，失败后由下面的 tx 回写状态）
        InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> initialCall;
        try {
            initialCall = operationSupport.callAiForFirstQuestion(afterTx1, userId);
        } catch (BusinessException e) {
            // AI 失败 → TX-error：把失败原因完整落到 attempt（含 retryable 与 providerRequestId），
            // 前端据此显示「重试」还是「重新授权」
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                operationSupport.markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                        operationSupport.isRetryable(e), operationSupport.providerRequestId(e));
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt failedAttempt = attemptRepository
                    .findTopBySessionIdAndStatusOrderByIdDesc(sessionId, AiAttemptStatus.FAILED).orElseThrow();
            return stateAssembler.buildStateResponse(failed, null,
                    stateAssembler.buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION", e));
        }

        // TX2: 保存 AI 结果
        // 状态守卫不可省略：AI 调用的几十秒里，会话可能已被 getState 的陈旧接管或用户降级改成别的状态；
        // 此时写回题目会覆盖更新的状态，所以必须抛 CONFLICT 让本次结果作废。
        tx.executeWithoutResult(s -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
            if (session.getStatus() != InterviewStatus.GENERATING_QUESTION) {
                throw new BusinessException(ErrorCode.CONFLICT, "会话状态已变更");
            }
            session.setCurrentQuestion(initialCall.value().getQuestion());
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
            sessionRepository.save(session);

            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            attempt.setStatus(AiAttemptStatus.SUCCESS);
            attempt.setResultJson(Map.of("question", initialCall.value().getQuestion()));
            attempt.setPendingAnswer(null);
            attempt.setProviderRequestId(initialCall.providerRequestId());
            attemptRepository.save(attempt);
        });

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        return stateAssembler.buildStateResponse(session, null, null);
    }
}
