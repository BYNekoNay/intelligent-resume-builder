package com.intelligentresume.interview.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskConsentPolicy;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面试薄弱项练习（follow-up）。
 *
 * <p>创建：复用 {@code INTERVIEW_COACH} AiTaskType（input.operation=FOLLOW_UP_PRACTICE），
 * 202 + taskId，前端轮询 {@code GET /api/ai/tasks/{id}}。
 * 执行：worker 中加载同源简历/JD（复用 {@link InterviewPromptContextAssembler} 脱敏），
 * 结合指定 weakness 生成 3~5 条候选练习题，结果写入 resultJson.candidates。
 *
 * <p>同意校验：复用 INTERVIEW_COACH consent scope（数据类别 RESUME/INTERVIEW_ANSWER/JD），
 * 不新增授权 scope，避免存量用户重新授权。
 */
@Service
public class InterviewFollowUpAiService {

    private static final Logger log = LoggerFactory.getLogger(InterviewFollowUpAiService.class);
    private static final String PROMPT_VERSION = "interview-follow-up-v1";
    private static final int MIN_CANDIDATES = 3;
    private static final int MAX_CANDIDATES = 5;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewFollowUpPromptAssembler promptAssembler;
    private final AiPreviewService previewService;
    private final AiPreviewGuard previewGuard;
    private final AiConsentService consentService;
    private final AiTaskService aiTaskService;
    private final AiProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public InterviewFollowUpAiService(InterviewSessionRepository sessionRepository,
                                      InterviewFollowUpPromptAssembler promptAssembler,
                                      AiConsentService consentService,
                                      AiTaskService aiTaskService,
                                      AiProviderRegistry providerRegistry,
                                      ObjectMapper objectMapper,
                                      Validator validator,
                                      AiPreviewService previewService,
                                      AiPreviewGuard previewGuard) {
        this.sessionRepository = sessionRepository;
        this.promptAssembler = promptAssembler;
        this.previewService = previewService;
        this.previewGuard = previewGuard;
        this.consentService = consentService;
        this.aiTaskService = aiTaskService;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validator = validator;
    }

    // ==================== 创建任务（领域端点） ====================

    @Transactional
    public AiTaskStatusResponse createFollowUpTask(Long sessionId, String weakness, Long userId,
                                                   String idempotencyKey, String previewToken) {
        InterviewSession session = ownedCompletedSession(sessionId, userId);
        String trimmedWeakness = validateWeakness(weakness);

        // 领域层同意校验与通用 AI task / worker 使用同一份类别策略。
        Map<String, Object> consentSnapshot = session.getJobDescriptionId() == null
                ? Map.of()
                : Map.of("jobDescriptionId", session.getJobDescriptionId());
        List<String> categories = AiTaskConsentPolicy.requiredCategories(AiTaskType.INTERVIEW_COACH,
                consentSnapshot);
        if (!consentService.hasValidConsent(userId, "INTERVIEW_COACH", categories)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED, "需要 AI 面试授权，请先同意隐私政策");
        }

        // R32：令牌用**服务端自己组装**的 providerInput 重算，与 worker 同源。
        Map<String, Object> providerInput = promptAssembler.buildProviderInput(session, trimmedWeakness, userId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", "FOLLOW_UP_PRACTICE");
        input.put("sessionId", sessionId);
        input.put("weakness", trimmedWeakness);
        input.put("promptVersion", PROMPT_VERSION);
        CreateAiTaskRequest taskRequest = new CreateAiTaskRequest(
                AiTaskType.INTERVIEW_COACH, input, null, session.getJobDescriptionId(), null, null, null, null);
        // 守卫位置由 AiTaskService 保证：幂等重放之后（重试不因缺令牌失败）、配额与写入之前。
        return aiTaskService.create(taskRequest, idempotencyKey, userId,
                () -> previewGuard.require(AiTaskType.INTERVIEW_COACH, providerInput, previewToken));
    }

    /**
     * 发送前预览（R32 AC-R32-8 / AC-R32-12）：返回**将要发送的确切 messages** 与令牌。
     *
     * <p>只读：不落库、不调 provider、不消耗配额、不校验 consent —— 预览只是让本人看清
     * 自己数据将被如何发送，授权是创建期的检查。
     */
    public AiPreviewService.PreviewResponse preview(Long sessionId, String weakness, Long userId) {
        InterviewSession session = ownedCompletedSession(sessionId, userId);
        String trimmedWeakness = validateWeakness(weakness);
        Map<String, Object> providerInput = promptAssembler.buildProviderInput(session, trimmedWeakness, userId);
        return previewService.preview(new AiPreviewService.PreviewRequest(
                AiTaskType.INTERVIEW_COACH, providerInput, false));
    }

    private InterviewSession ownedCompletedSession(Long sessionId, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));
        if (session.getStatus() != InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "会话未完成，无法生成针对性练习");
        }
        return session;
    }

    private String validateWeakness(String weakness) {
        String trimmedWeakness = weakness == null ? "" : weakness.trim();
        if (trimmedWeakness.isBlank() || trimmedWeakness.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION, "薄弱项不能为空且不超过 500 字");
        }
        return trimmedWeakness;
    }

    // ==================== worker 执行 ====================

    public Map<String, Object> executeTask(AiTask task) {
        Map<String, Object> input = providerInput(task);
        Long sessionId = toLong(input.get("sessionId"));
        String weakness = input.get("weakness") == null ? "" : input.get("weakness").toString();
        if (sessionId == null || weakness.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "follow-up 任务缺少 sessionId 或 weakness");
        }
        Long userId = task.getUserId();
        InterviewSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));

        AiCallResult result = callProvider(promptAssembler.buildProviderInput(session, weakness, userId));
        FollowUpResponse response = parseResult(result.data());
        List<Candidate> candidates = response.getCandidates();
        if (candidates == null || candidates.size() < MIN_CANDIDATES || candidates.size() > MAX_CANDIDATES) {
            throw new BusinessException(ErrorCode.AI_FAILURE,
                    "AI 返回练习题数量不符合要求（3~5 条）");
        }
        for (Candidate candidate : candidates) {
            validateCandidate(candidate);
        }

        List<Map<String, Object>> candidateMaps = candidates.stream().map(candidate -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("question", candidate.getQuestion());
            map.put("focus", candidate.getFocus());
            map.put("expectedSignals", candidate.getExpectedSignals());
            map.put("coverageTags", candidate.getCoverageTags());
            return map;
        }).toList();

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("operation", "FOLLOW_UP_PRACTICE");
        resultJson.put("promptVersion", PROMPT_VERSION);
        resultJson.put("weakness", weakness);
        resultJson.put("candidates", candidateMaps);
        return resultJson;
    }

    private AiCallResult callProvider(Map<String, Object> providerInput) {
        AiCallContext ctx = new AiCallContext(AiTaskType.INTERVIEW_COACH, providerInput);
        AiCallResult result;
        try {
            result = providerRegistry.route(AiTaskType.INTERVIEW_COACH).call(ctx);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Interview follow-up provider raised an unexpected error: exception={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 调用失败");
        }
        if (!result.success() || result.data() == null) {
            String msg = result.errorMessage() != null ? result.errorMessage() : "AI 调用失败";
            log.warn("Interview follow-up AI call failed: {}", msg);
            throw new BusinessException(ErrorCode.AI_FAILURE, msg);
        }
        return result;
    }

    private FollowUpResponse parseResult(Map<String, Object> data) {
        try {
            return objectMapper.convertValue(data, FollowUpResponse.class);
        } catch (Exception e) {
            // 隐私约束：Jackson 解析错误信息可能回显模型原文片段，只记录异常类型
            log.warn("Failed to parse interview follow-up response: exception={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 返回数据格式无效");
        }
    }

    private void validateCandidate(Candidate candidate) {
        Set<ConstraintViolation<Candidate>> violations = validator.validate(candidate);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 返回练习题不符合契约要求");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerInput(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot != null && snapshot.get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        return snapshot != null ? snapshot : Map.of();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    // ---- 响应 DTO ----

    public static class FollowUpResponse {
        private List<Candidate> candidates;

        public List<Candidate> getCandidates() { return candidates; }
        public void setCandidates(List<Candidate> candidates) { this.candidates = candidates; }
    }

    public static class Candidate {
        @NotBlank
        @Size(min = 10, max = 500)
        private String question;

        @NotBlank
        @Size(min = 1, max = 100)
        private String focus;

        @NotNull
        @Size(min = 1, max = 5)
        private List<@Size(max = 300) String> expectedSignals;

        @NotNull
        @Size(min = 1, max = 3)
        private List<@Size(max = 50) String> coverageTags;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getFocus() { return focus; }
        public void setFocus(String focus) { this.focus = focus; }
        public List<String> getExpectedSignals() { return expectedSignals; }
        public void setExpectedSignals(List<String> expectedSignals) { this.expectedSignals = expectedSignals; }
        public List<String> getCoverageTags() { return coverageTags; }
        public void setCoverageTags(List<String> coverageTags) { this.coverageTags = coverageTags; }
    }
}
