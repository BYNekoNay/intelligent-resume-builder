package com.intelligentresume.interview.service;

import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewReportResponse;
import com.intelligentresume.interview.dto.InterviewSessionSummaryResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试服务门面（原上帝类拆分后）。
 *
 * <p>仅保留 {@code getState} 与 8 个 public 签名的纯委托，不含业务实现；
 * 不允许在门面私有方法内调用 @Transactional 方法（避免绕过代理）。
 *
 * <p>流程委托关系：start→{@link InterviewStartService}；answer→{@link InterviewAnswerService}；
 * retryAi→{@link InterviewRetryService}；continueWithRules/ruleAnswer→{@link InterviewRuleService}；
 * finish/report→{@link InterviewReportService}。
 */
@Service
public class InterviewService {

    private final InterviewStartService startService;
    private final InterviewAnswerService answerService;
    private final InterviewRetryService retryService;
    private final InterviewRuleService ruleService;
    private final InterviewReportService reportService;
    private final InterviewHistoryService historyService;
    private final InterviewFollowUpAiService followUpService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewService(InterviewStartService startService,
                            InterviewAnswerService answerService,
                            InterviewRetryService retryService,
                            InterviewRuleService ruleService,
                            InterviewReportService reportService,
                            InterviewHistoryService historyService,
                            InterviewFollowUpAiService followUpService,
                            InterviewSessionRepository sessionRepository,
                            InterviewAiAttemptRepository attemptRepository,
                            TransactionTemplate tx,
                            InterviewStateAssembler stateAssembler,
                            InterviewOperationSupport operationSupport) {
        this.startService = startService;
        this.answerService = answerService;
        this.retryService = retryService;
        this.ruleService = ruleService;
        this.reportService = reportService;
        this.historyService = historyService;
        this.followUpService = followUpService;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.tx = tx;
        this.stateAssembler = stateAssembler;
        this.operationSupport = operationSupport;
    }

    // ==================== 流程委托 ====================

    public InterviewStateResponse start(StartInterviewRequest request, Long userId, String idempotencyKey) {
        return startService.start(request, userId, idempotencyKey);
    }

    public InterviewStateResponse answer(Long id, String answerText, Long userId, String idempotencyKey) {
        return answerService.answer(id, answerText, userId, idempotencyKey);
    }

    public InterviewStateResponse retryAi(Long id, Long userId) {
        return retryService.retryAi(id, userId);
    }

    public InterviewStateResponse continueWithRules(Long id, Long userId) {
        return ruleService.continueWithRules(id, userId);
    }

    public InterviewStateResponse ruleAnswer(Long id, String answerText, Long userId, String idempotencyKey) {
        return ruleService.ruleAnswer(id, answerText, userId, idempotencyKey);
    }

    public InterviewStateResponse finish(Long id, Long userId) {
        return reportService.finish(id, userId);
    }

    public InterviewReportResponse report(Long id, Long userId) {
        return reportService.report(id, userId);
    }

    public List<InterviewSessionSummaryResponse> listHistory(Long userId, Long jobDescriptionId) {
        return historyService.list(userId, jobDescriptionId);
    }

    public AiTaskStatusResponse createFollowUp(Long id, String weakness, Long userId, String idempotencyKey) {
        return followUpService.createFollowUpTask(id, weakness, userId, idempotencyKey);
    }

    // ==================== 会话状态（留在门面，Controller answer() 依赖其路由） ====================

    public InterviewStateResponse getState(Long id, Long userId) {
        return tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));
            if (session.getStatus() == InterviewStatus.GENERATING_QUESTION
                    || session.getStatus() == InterviewStatus.EVALUATING_ANSWER) {
                attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDesc(
                                id, AiAttemptStatus.PROCESSING)
                        .filter(attempt -> operationSupport.isStale(attempt, LocalDateTime.now()))
                        .ifPresent(attempt -> operationSupport.markAttemptFailed(session, attempt,
                                "PROCESSING_TIMEOUT", "AI 请求处理超时，请重试", true, null));
            }
            InterviewStateResponse.AiFailureInfo failure = session.getStatus() == InterviewStatus.AI_ACTION_REQUIRED
                    ? stateAssembler.latestFailedAttempt(id).map(stateAssembler::buildAiFailure).orElse(null) : null;
            return stateAssembler.buildStateResponse(session, null, failure);
        });
    }
}
