package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsAnalysisSource;
import com.intelligentresume.ats.dto.AtsAnalysisStatus;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ATS 检查记录里 {@code result_json} 的状态迁移：ANALYZING → COMPLETED / RULES_FALLBACK。
 *
 * <p>要点：
 * <ul>
 *   <li>所有写操作都在悲观锁内"读 → 改字段 → 写回"（{@code findOwnedForUpdate}），
 *       因为 {@code result_json} 是整列覆盖：不锁会丢并发写入的字段。</li>
 *   <li>{@code analysisStatus} 与 {@code analysisSource} 成对出现：ANALYZING / RULES_FALLBACK 配
 *       {@code RULES}，只有 COMPLETED 配 {@code HYBRID}。只改其中一个会让前端读到自相矛盾的状态。</li>
 *   <li>回写前用 {@code aiTaskId} 比对"我是不是当前任务"。用户重试后会产生新 taskId，
 *       旧的 worker 迟到写回必须被丢弃（返回 false），否则会把新任务的结果覆盖成旧的。</li>
 *   <li>{@code markFallback} 在 taskId 为 null 时**跳过**这一步比对 —— 启动阶段失败时还没有任务 ID。</li>
 *   <li>{@code aiTaskId} 只认 Number：本类不做字符串解析，历史数据里若存成字符串会被判成"非当前任务"。</li>
 * </ul>
 */
@Service
public class AtsResultStateService {
    private final AtsCheckResultRepository repository;
    private final ObjectMapper objectMapper;

    public AtsResultStateService(AtsCheckResultRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 进入分析中：写入 taskId 并把上一轮的 aiInsights / fallback 清空。
     *
     * <p>不做 taskId 比对 —— 调用它的就是刚刚创建/重试任务的一方，此时记录必须无条件指向新任务。
     */
    @Transactional
    public void markAnalyzing(Long resultId, Long userId, Long taskId) {
        AtsCheckResult result = ownedForUpdate(resultId, userId);
        Map<String, Object> json = copy(result.getResultJson());
        json.put("analysisStatus", AtsAnalysisStatus.ANALYZING.name());
        json.put("analysisSource", AtsAnalysisSource.RULES.name());
        json.put("aiTaskId", taskId);
        json.put("aiInsights", null);
        json.put("fallback", null);
        result.setResultJson(json);
        repository.save(result);
    }

    /**
     * 写入 AI 结论。
     *
     * @return false 表示记录已经被别的任务接手（taskId 不匹配），调用方必须当"丢弃"处理、不要重试写入
     */
    @Transactional
    public boolean markCompleted(Long resultId, Long userId, Long taskId, AtsAiInsights insights) {
        AtsCheckResult result = ownedForUpdate(resultId, userId);
        Map<String, Object> json = copy(result.getResultJson());
        if (!isCurrentTask(json, taskId)) return false;
        json.put("analysisStatus", AtsAnalysisStatus.COMPLETED.name());
        json.put("analysisSource", AtsAnalysisSource.HYBRID.name());
        json.put("aiInsights", objectMapper.convertValue(insights, Map.class));
        json.put("fallback", null);
        result.setResultJson(json);
        repository.save(result);
        return true;
    }

    /**
     * 落兜底：状态改 RULES_FALLBACK、清空 aiInsights、写入 {@code fallback{code,message,retryable,consentRequired}}。
     *
     * <p>{@code retryable} 决定前端要不要给"重试 AI"按钮，{@code consentRequired} 决定要不要先弹授权 ——
     * 这两个布尔与 {@code code} 必须一起改，只改 code 会让按钮显示错。
     *
     * @return false 表示记录已被别的任务接手（taskId 不匹配，且 taskId 非 null）
     */
    @Transactional
    public boolean markFallback(Long resultId, Long userId, Long taskId, AtsFallbackCode code,
                                String message, boolean retryable, boolean consentRequired) {
        AtsCheckResult result = ownedForUpdate(resultId, userId);
        Map<String, Object> json = copy(result.getResultJson());
        if (taskId != null && !isCurrentTask(json, taskId)) return false;
        json.put("analysisStatus", AtsAnalysisStatus.RULES_FALLBACK.name());
        json.put("analysisSource", AtsAnalysisSource.RULES.name());
        json.put("aiInsights", null);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("code", code.name());
        fallback.put("message", message);
        fallback.put("retryable", retryable);
        fallback.put("consentRequired", consentRequired);
        json.put("fallback", fallback);
        result.setResultJson(json);
        repository.save(result);
        return true;
    }

    /**
     * 包内共享：把 resultJson 里可能来自 JSON 反序列化的数值统一取成 {@code Long}。
     *
     * <p>为什么要共用：{@code AtsService} 与 {@link AtsAiStarter} 都要读 {@code aiTaskId}，
     * 各写一份 instanceof 判断就会出现两份口径（本仓已多次因此吃亏）。
     */
    static Long longValueOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private AtsCheckResult ownedForUpdate(Long resultId, Long userId) {
        return repository.findOwnedForUpdate(resultId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
    }

    /**
     * 判断记录当前指向的是不是这次任务。
     *
     * <p>注意 {@code number.longValue() == taskId} 会触发 {@code Long} 自动拆箱：
     * {@code taskId} 为 null 时这里抛 NPE。{@code markFallback} 已在调用前挡掉 null，
     * {@code markCompleted} 没有挡 —— 调用方必须保证 taskId 非空。
     */
    private boolean isCurrentTask(Map<String, Object> json, Long taskId) {
        Object current = json.get("aiTaskId");
        return current instanceof Number number && number.longValue() == taskId;
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
