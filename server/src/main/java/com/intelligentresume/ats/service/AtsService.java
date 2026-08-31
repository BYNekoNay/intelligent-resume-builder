package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.ai.task.service.IdempotencyService;
import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.*;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.service.ScoringService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AtsService {
    private static final String DISCLAIMER = "规则分数与 AI 建议均用于简历改进参考，不代表真实企业 ATS 结果、录用概率或招聘决定。";

    private final ScoringService scoringService;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final AtsCheckResultRepository repository;
    private final AiTaskService taskService;
    private final IdempotencyService idempotencyService;
    private final AiProviderRegistry providerRegistry;
    private final AtsResultStateService stateService;
    private final AtsAiPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final String promptVersion;
    private final String schemaVersion;

    public AtsService(ScoringService scoringService, ResumeVersionRepository versionRepository,
                      ResumeRepository resumeRepository, JobDescriptionRepository jobRepository,
                      AtsCheckResultRepository repository, AiTaskService taskService, IdempotencyService idempotencyService,
                      AiProviderRegistry providerRegistry, AtsResultStateService stateService,
                      AtsAiPromptBuilder promptBuilder, ObjectMapper objectMapper,
                      @Value("${app.ai.ats.prompt-version:v1.0.0}") String promptVersion,
                      @Value("${app.ai.ats.schema-version:v1.0.0}") String schemaVersion) {
        this.scoringService = scoringService;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.repository = repository;
        this.taskService = taskService;
        this.idempotencyService = idempotencyService;
        this.providerRegistry = providerRegistry;
        this.stateService = stateService;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.promptVersion = promptVersion;
        this.schemaVersion = schemaVersion;
    }

    public AtsCheckResponse check(AtsCheckRequest request, String idempotencyKey, Long userId) {
        String fingerprint = idempotencyService.fingerprint(Map.of(
                "resumeVersionId", request.resumeVersionId(), "jobDescriptionId", request.jobDescriptionId(),
                "useAi", request.shouldUseAi()));
        var existing = repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            if (fingerprint.equals(existing.get().getRequestFingerprint())) return toResponse(existing.get());
            throw new BusinessException(ErrorCode.CONFLICT, "相同幂等键的请求内容不一致");
        }
        ResumeVersion version = ownedVersion(request.resumeVersionId(), userId);
        JobDescription job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
        MatchResponse match = scoringService.score(
                new MatchRequest(request.resumeVersionId(), request.jobDescriptionId()), userId);

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("structure", structureScore(version.getResumeJson()));
        checks.put("keywordCoverage", match.keywordScore());
        checks.put("skillCoverage", match.skillScore());
        checks.put("experienceCoverage", match.experienceScore());

        int structure = (int) checks.get("structure");
        List<String> passed = new ArrayList<>();
        if (structure >= 75) passed.add("简历核心结构完整");
        if (atLeast(match.keywordScore(), 70)) passed.add("JD 关键词覆盖良好");
        if (atLeast(match.skillScore(), 70)) passed.add("技能章节覆盖良好");

        List<String> risks = new ArrayList<>();
        if (structure < 75) risks.add("简历缺少姓名、经历、技能或教育等核心结构");
        if (!match.explanation().missing().isEmpty()) {
            risks.add("缺少 JD 关键词: " + String.join(", ", match.explanation().missing()));
        }

        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("checks", checks);
        persisted.put("passedChecks", passed);
        persisted.put("risks", risks);
        persisted.put("priorities", match.explanation().suggestions());
        persisted.put("disclaimer", DISCLAIMER);
        persisted.put("analysisStatus", request.shouldUseAi()
                ? AtsAnalysisStatus.RULES_FALLBACK.name() : AtsAnalysisStatus.RULES_ONLY.name());
        persisted.put("analysisSource", AtsAnalysisSource.RULES.name());
        persisted.put("aiTaskId", null);
        persisted.put("aiInsights", null);
        persisted.put("fallback", null);
        persisted.put("promptVersion", promptVersion);
        persisted.put("schemaVersion", schemaVersion);

        AtsCheckResult entity = new AtsCheckResult();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestFingerprint(fingerprint);
        entity.setTotalScore(match.totalScore());
        entity.setResultJson(persisted);
        try {
            entity = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException collision) {
            // A concurrent retry can pass the initial lookup before the unique key is inserted.
            // Resolve it to the same response rather than leaking a persistence error to the client.
            AtsCheckResult winner = repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> collision);
            if (fingerprint.equals(winner.getRequestFingerprint())) return toResponse(winner);
            throw new BusinessException(ErrorCode.CONFLICT, "相同幂等键的请求内容不一致");
        }

        if (request.shouldUseAi()) {
            startAiOrFallback(entity, version, job, userId);
        }
        return get(entity.getId(), userId);
    }

    @Transactional(readOnly = true)
    public AtsCheckResponse get(Long id, Long userId) {
        AtsCheckResult result = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
        return toResponse(result);
    }

    public AtsCheckResponse retryAi(Long id, Long userId) {
        AtsCheckResult result = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
        ResumeVersion version = ownedVersion(result.getResumeVersionId(), userId);
        JobDescription job = jobRepository.findByIdAndUserId(result.getJobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));

        Long taskId = longValue(result.getResultJson().get("aiTaskId"));
        try {
            if (!providerRegistry.route(AiTaskType.ATS_ANALYSIS).isAvailable()) {
                return fallback(result, userId, taskId, AtsFallbackCode.AI_DISABLED,
                        "AI 服务尚未配置，已使用本地规则结果。", false, false);
            }
            if (taskId != null) {
                AiTaskStatusResponse task = taskService.get(taskId, userId);
                if (task.status() == AiTaskStatus.FAILED) {
                    AiTaskStatusResponse retried = taskService.retry(taskId, userId);
                    stateService.markAnalyzing(id, userId, retried.id());
                    return get(id, userId);
                }
                if (task.status() == AiTaskStatus.PENDING || task.status() == AiTaskStatus.RUNNING) {
                    stateService.markAnalyzing(id, userId, task.id());
                    return get(id, userId);
                }
                if (task.status() == AiTaskStatus.SUCCESS) return get(id, userId);
            }
            createTask(result, version, job, userId);
        } catch (BusinessException e) {
            applyExpectedFallback(result, userId, taskId, e);
        }
        return get(id, userId);
    }

    private void startAiOrFallback(AtsCheckResult result, ResumeVersion version, JobDescription job, Long userId) {
        try {
            if (!providerRegistry.route(AiTaskType.ATS_ANALYSIS).isAvailable()) {
                stateService.markFallback(result.getId(), userId, null, AtsFallbackCode.AI_DISABLED,
                        "AI 服务尚未配置，已使用本地规则结果。", false, false);
                return;
            }
            createTask(result, version, job, userId);
        } catch (BusinessException e) {
            applyExpectedFallback(result, userId, null, e);
        } catch (RuntimeException e) {
            stateService.markFallback(result.getId(), userId, null, AtsFallbackCode.UNKNOWN,
                    "AI 分析未能启动，已使用本地规则结果。", true, false);
        }
    }

    private void createTask(AtsCheckResult result, ResumeVersion version, JobDescription job, Long userId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("atsCheckResultId", result.getId());
        input.put("resumeVersionId", result.getResumeVersionId());
        input.put("jobDescriptionId", result.getJobDescriptionId());
        input.put("resumeJson", promptBuilder.sanitizeResume(version.getResumeJson()));
        input.put("jdText", job.getJdText());
        input.put("localResult", localSnapshot(result.getResultJson()));
        input.put("promptVersion", promptVersion);
        input.put("schemaVersion", schemaVersion);
        CreateAiTaskRequest request = new CreateAiTaskRequest(
                AiTaskType.ATS_ANALYSIS, promptBuilder.sanitizeInput(input), null, result.getJobDescriptionId(),
                null, null, null, null);
        AiTaskStatusResponse task = taskService.create(request, "ats:" + result.getId() + ":v1", userId);
        stateService.markAnalyzing(result.getId(), userId, task.id());
    }

    private void applyExpectedFallback(AtsCheckResult result, Long userId, Long taskId, BusinessException e) {
        if (e.getErrorCode() == ErrorCode.CONSENT_REQUIRED) {
            stateService.markFallback(result.getId(), userId, taskId, AtsFallbackCode.CONSENT_REQUIRED,
                    "需要授权 AI 处理简历和岗位信息，当前已使用本地规则结果。", false, true);
        } else if (e.getErrorCode() == ErrorCode.RATE_LIMITED) {
            stateService.markFallback(result.getId(), userId, taskId, AtsFallbackCode.QUOTA_EXCEEDED,
                    "今日 AI ATS 分析配额已用完，当前已使用本地规则结果。", false, false);
        } else {
            stateService.markFallback(result.getId(), userId, taskId, AtsFallbackCode.UNKNOWN,
                    "AI 分析未能启动，已使用本地规则结果。", true, false);
        }
    }

    private AtsCheckResponse fallback(AtsCheckResult result, Long userId, Long taskId, AtsFallbackCode code,
                                      String message, boolean retryable, boolean consentRequired) {
        stateService.markFallback(result.getId(), userId, taskId, code, message, retryable, consentRequired);
        return get(result.getId(), userId);
    }

    private ResumeVersion ownedVersion(Long versionId, Long userId) {
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (version.getDeletedAt() != null || resumeRepository.findByIdAndUserId(version.getResumeId(), userId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        return version;
    }

    private Map<String, Object> localSnapshot(Map<String, Object> source) {
        Map<String, Object> local = new LinkedHashMap<>();
        for (String key : List.of("checks", "passedChecks", "risks", "priorities", "disclaimer")) {
            local.put(key, source.get(key));
        }
        return local;
    }

    private AtsCheckResponse toResponse(AtsCheckResult entity) {
        Map<String, Object> json = entity.getResultJson();
        AtsAnalysisStatus analysisStatus = enumValue(AtsAnalysisStatus.class, json.get("analysisStatus"), AtsAnalysisStatus.RULES_FALLBACK);
        Long resumeId = analysisStatus == AtsAnalysisStatus.ANALYZING ? null : versionRepository.findById(entity.getResumeVersionId())
                .map(ResumeVersion::getResumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        return new AtsCheckResponse(
                entity.getId(), resumeId, entity.getResumeVersionId(), entity.getJobDescriptionId(),
                entity.getTotalScore(), map(json.get("checks")),
                strings(json.get("passedChecks")), strings(json.get("risks")), strings(json.get("priorities")),
                String.valueOf(json.getOrDefault("disclaimer", DISCLAIMER)),
                analysisStatus,
                enumValue(AtsAnalysisSource.class, json.get("analysisSource"), AtsAnalysisSource.RULES),
                longValue(json.get("aiTaskId")), convert(json.get("aiInsights"), AtsAiInsights.class),
                convert(json.get("fallback"), AtsFallbackInfo.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private <T> T convert(Object value, Class<T> type) {
        return value == null ? null : objectMapper.convertValue(value, type);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, String.valueOf(value)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private boolean atLeast(BigDecimal score, int threshold) {
        return score.compareTo(BigDecimal.valueOf(threshold)) >= 0;
    }

    private int structureScore(Map<String, Object> resume) {
        if (resume == null) return 0;
        int score = 0;
        Object basicsValue = resume.get("basics");
        if (basicsValue instanceof Map<?, ?> basics
                && basics.get("name") instanceof String name && !name.isBlank()) score += 25;
        if (nonEmptyList(resume.get("work"))) score += 25;
        if (nonEmptyList(resume.get("skills"))) score += 25;
        if (nonEmptyList(resume.get("education")) || nonEmptyList(resume.get("projects"))) score += 25;
        return score;
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> values && !values.isEmpty();
    }
}
