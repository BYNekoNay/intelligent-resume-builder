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

@Service
public class AtsResultStateService {
    private final AtsCheckResultRepository repository;
    private final ObjectMapper objectMapper;

    public AtsResultStateService(AtsCheckResultRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

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

    private AtsCheckResult ownedForUpdate(Long resultId, Long userId) {
        return repository.findOwnedForUpdate(resultId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
    }

    private boolean isCurrentTask(Map<String, Object> json, Long taskId) {
        Object current = json.get("aiTaskId");
        return current instanceof Number number && number.longValue() == taskId;
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
