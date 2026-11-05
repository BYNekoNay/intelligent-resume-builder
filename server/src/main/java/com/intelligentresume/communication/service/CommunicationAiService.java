package com.intelligentresume.communication.service;

import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 沟通文案的 worker 侧执行：重建 prompt → 调 provider → 校验/修复 → 组装结果。
 *
 * <p>要点：
 * <ul>
 *   <li><b>prompt 不用任务里存的快照再拼一次</b>，而是由 {@code promptBuilder.build(input)} 现场生成，
 *       因此本类与 {@code CommunicationService#prepareInput} 必须保持同一份 input 结构。</li>
 *   <li><b>校验失败只重试一次</b>：用 {@code buildRepair} 带上首次错误原因重问；再失败直接抛出，
 *       不做「清洗后放行」。</li>
 *   <li><b>草稿不在此落库</b>：只返回 {@link PendingDraft}，由 worker 在租约校验通过后同一事务内
 *       调 {@link #persistDraft}，避免租约被接管时留下孤儿草稿。</li>
 *   <li><b>输入只认 {@code inputSnapshotJson.input}</b>：缺失或类型转换失败均抛不可重试异常，
 *       重试不会让坏输入变好。</li>
 * </ul>
 */
@Service
public class CommunicationAiService {
    private final AiProviderRegistry providerRegistry;
    private final CommunicationAiPromptBuilder promptBuilder;
    private final CommunicationAiResultValidator validator;
    private final CommunicationDraftRepository draftRepository;

    public CommunicationAiService(AiProviderRegistry providerRegistry, CommunicationAiPromptBuilder promptBuilder,
                                  CommunicationAiResultValidator validator, CommunicationDraftRepository draftRepository) {
        this.providerRegistry = providerRegistry;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.draftRepository = draftRepository;
    }

    /**
     * 执行一次生成任务，返回「任务结果」与「待落库草稿」两段。
     *
     * <p>顺序要求：先跑完 provider 与校验，再解析 id —— 前面的异常要按 AI 失败处理，
     * 不能被 id 解析错误盖掉成另一种失败原因。
     */
    public ExecutionResult executeTask(AiTask task) {
        Map<String, Object> input = taskInput(task);
        CommunicationType type = parseType(input.get("type"));
        String outputLanguage = "EN".equals(input.get("outputLanguage")) ? "EN" : "ZH_CN";
        AiProvider provider = providerRegistry.route(AiTaskType.COMMUNICATION_GENERATE);
        AiCallResult response = call(provider, promptBuilder.build(input));
        CommunicationAiResultValidator.ValidatedResult validated;
        try {
            validated = validator.validate(response.data(), type, outputLanguage);
        } catch (CommunicationAiException firstError) {
            response = call(provider, promptBuilder.buildRepair(input, response.data(), firstError.getMessage()));
            validated = validator.validate(response.data(), type, outputLanguage);
        }

        String draft = composeDraft(validated, type, outputLanguage);
        long resumeVersionId = longValue(input.get("resumeVersionId"), "resumeVersionId");
        long jobDescriptionId = longValue(input.get("jobDescriptionId"), "jobDescriptionId");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.name());
        result.put("subject", validated.subject());
        result.put("body", validated.body());
        result.put("draft", draft);
        result.put("generationSource", "AI");
        result.put("resumeVersionId", resumeVersionId);
        result.put("jobDescriptionId", jobDescriptionId);
        result.put("promptVersion", promptBuilder.promptVersion());
        result.put("schemaVersion", promptBuilder.schemaVersion());
        result.put("providerRequestId", response.providerRequestId());
        // #29：草稿落库不在本方法内完成——必须与任务结果写入同一事务（租约校验通过后才写），
        // 否则租约被接管时旧 worker 会留下引用不到任何任务的孤儿草稿。
        return new ExecutionResult(result,
                new PendingDraft(task.getUserId(), resumeVersionId, jobDescriptionId, type, draft));
    }

    /**
     * 草稿落库（#29）。由 worker 在 {@code TaskLeaseService.releaseSuccess} 的租约校验通过后、
     * 同一事务内回调，并把生成的草稿 id 写回任务结果。
     */
    public void persistDraft(PendingDraft pending, Map<String, Object> result) {
        CommunicationDraft draftToSave = new CommunicationDraft();
        draftToSave.setUserId(pending.userId());
        draftToSave.setResumeVersionId(pending.resumeVersionId());
        draftToSave.setJobDescriptionId(pending.jobDescriptionId());
        draftToSave.setType(pending.type());
        draftToSave.setDraftText(pending.draftText());
        CommunicationDraft entity = draftRepository
                .findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
                        pending.userId(), pending.resumeVersionId(), pending.jobDescriptionId(),
                        pending.type(), pending.draftText())
                .orElseGet(() -> draftRepository.save(draftToSave));
        result.put("communicationDraftId", entity.getId());
    }

    private AiCallResult call(AiProvider provider, Map<String, Object> prompt) {
        AiCallResult result = provider.call(new AiCallContext(AiTaskType.COMMUNICATION_GENERATE, prompt));
        if (result.success()) return result;
        throw new CommunicationAiException(
                result.errorMessage() == null ? "Communication AI provider failed" : result.errorMessage(),
                result.retryable());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskInput(AiTask task) {
        if (task.getInputSnapshotJson() != null && task.getInputSnapshotJson().get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        throw new CommunicationAiException("Communication AI task input is missing", false);
    }

    private CommunicationType parseType(Object value) {
        try {
            return CommunicationType.valueOf(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new CommunicationAiException("Communication type is invalid", false);
        }
    }

    private long longValue(Object value, String name) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new CommunicationAiException(name + " is invalid", false);
        }
    }

    /**
     * EMAIL 才把 subject 前缀拼进正文，其余类型只有 body。
     *
     * <p>拼装格式必须与「模板生成」路径一致（{@code 主题：xxx\n\n正文} / {@code Subject: xxx\n\n正文}），
     * 否则同一份邮件在两条生成路径下形态不同，前端复制行为会出现差异。
     */
    private String composeDraft(CommunicationAiResultValidator.ValidatedResult result,
                                CommunicationType type, String language) {
        if (type != CommunicationType.EMAIL) return result.body();
        return ("EN".equals(language) ? "Subject: " : "主题：") + result.subject() + "\n\n" + result.body();
    }

    public record PendingDraft(Long userId, long resumeVersionId, long jobDescriptionId,
                               CommunicationType type, String draftText) {
    }

    public record ExecutionResult(Map<String, Object> taskResult, PendingDraft pendingDraft) {
    }
}
