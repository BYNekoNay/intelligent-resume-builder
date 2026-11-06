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
 * <p>【小白导读】
 * ① 业务问题：AI 模拟面试的「总前台」。用户从选简历开始一场面试、逐轮作答、AI 点评、
 *    半途重试/转规则模式、结束、看五维报告、领薄弱项练习题——所有动作都先到这里，
 *    再由它转发给对应的流程服务。
 * ② 链路位置：上游只被 {@code InterviewController} 调用；下游把活儿分给
 *    {@link InterviewStartService}（开始）、{@link InterviewAnswerService}（作答）、
 *    {@link InterviewRetryService}（重试）、{@link InterviewRuleService}（规则降级）、
 *    {@link InterviewReportService}（结束/报告）、{@link InterviewHistoryService}（历史列表）、
 *    {@link InterviewFollowUpAiService}（追问练习）。对外产出统一是
 *    {@link InterviewStateResponse}（会话当前状态快照）。
 * ③ 改坏它：所有面试接口一起挂——用户点「开始面试」就报错，整个模拟面试功能不可用。
 *
 * <p>除 {@code getState} 外，其余 public 方法都是纯委托，不含业务实现；
 * 不允许在门面私有方法内调用 @Transactional 方法（避免绕过代理）。
 *
 * <p>流程委托关系：start→{@link InterviewStartService}；answer→{@link InterviewAnswerService}；
 * retryAi→{@link InterviewRetryService}；continueWithRules/ruleAnswer→{@link InterviewRuleService}；
 * finish/report→{@link InterviewReportService}；listHistory→{@link InterviewHistoryService}；
 * createFollowUp/previewFollowUp→{@link InterviewFollowUpAiService}。
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
    // 这里的每个方法都必须是「一行转发」：加逻辑就绕过了被委托类的短事务边界与锁顺序。

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

    public AiTaskStatusResponse createFollowUp(Long id, String weakness, Long userId, String idempotencyKey,
                                               String previewToken) {
        return followUpService.createFollowUpTask(id, weakness, userId, idempotencyKey, previewToken);
    }

    /** 追问练习的发送前预览（R32）：只读，不落库、不调 provider。 */
    public com.intelligentresume.ai.preview.AiPreviewService.PreviewResponse previewFollowUp(
            Long id, String weakness, Long userId) {
        return followUpService.preview(id, weakness, userId);
    }

    // ==================== 会话状态（留在门面，Controller answer() 依赖其路由） ====================

    /**
     * 读取会话状态；顺带做一次「陈旧 PROCESSING 接管」。
     *
     * <p>【小白导读】这是前端轮询的接口：AI 出题/点评期间，前端每隔几秒调一次它来问「好了没」。
     * 步骤：①加行锁读会话（行锁=数据库层面排队，保证同一会话同时只有一个请求能改它）
     * → ②若 AI 还在跑，检查有没有「卡死超时」的 attempt（一次 AI 尝试的记录，见
     * {@link InterviewAiAttempt}），有就判死并让会话进入「需要用户处理」状态
     * → ③若会话已处于 AI_ACTION_REQUIRED，顺带取出最近一次失败原因给前端展示
     * → ④组装状态快照返回。
     *
     * <p>为什么读取也要写：前端在 {@code GENERATING_QUESTION}/{@code EVALUATING_ANSWER} 下会持续轮询本接口，
     * 若后台线程已死（进程重启、线程池异常），没有任何请求会推进状态，会话就永久卡住。
     * 这里发现 attempt 超过阈值仍未完成就把它判死，会话随之进入 {@code AI_ACTION_REQUIRED}。
     *
     * <p>注意行锁：本方法用 {@code findByIdAndUserIdForUpdate}，因为可能写入。
     * 只有 {@code AI_ACTION_REQUIRED} 才查最近一次失败 attempt —— 其他状态下的失败信息对前端无意义。
     */
    public InterviewStateResponse getState(Long id, Long userId) {
        return tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));
            if (session.getStatus() == InterviewStatus.GENERATING_QUESTION
                    || session.getStatus() == InterviewStatus.EVALUATING_ANSWER) {
                attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc(
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
