package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.preview.AiPreviewService;
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
    private final IdempotencyService idempotencyService;
    private final AtsAiPromptBuilder promptBuilder;
    private final AtsAiStarter aiStarter;
    private final ObjectMapper objectMapper;

    public AtsService(ScoringService scoringService, ResumeVersionRepository versionRepository,
                      ResumeRepository resumeRepository, JobDescriptionRepository jobRepository,
                      AtsCheckResultRepository repository, IdempotencyService idempotencyService,
                      AtsAiPromptBuilder promptBuilder, AtsAiStarter aiStarter,
                      ObjectMapper objectMapper) {
        this.scoringService = scoringService;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.promptBuilder = promptBuilder;
        this.aiStarter = aiStarter;
        this.objectMapper = objectMapper;
    }

    public AtsCheckResponse check(AtsCheckRequest request, String idempotencyKey, Long userId,
                            String previewToken) {
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
        persisted.put("promptVersion", promptBuilder.promptVersion());
        persisted.put("schemaVersion", promptBuilder.schemaVersion());

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
            aiStarter.startOrFallback(entity, version, job, userId, previewToken);
        }
        return get(entity.getId(), userId);
    }

    @Transactional(readOnly = true)
    public AtsCheckResponse get(Long id, Long userId) {
        AtsCheckResult result = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
        return toResponse(result);
    }

    public AtsCheckResponse retryAi(Long id, Long userId, String previewToken) {
        AtsCheckResult result = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
        ResumeVersion version = ownedVersion(result.getResumeVersionId(), userId);
        JobDescription job = jobRepository.findByIdAndUserId(result.getJobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
        aiStarter.retryOrThrow(result, version, job, userId, previewToken);
        return get(id, userId);
    }

    /** 发送前预览（R32）：按 checkId 组装，只读 —— 不落库、不调 provider、不改检查状态。 */
    public AiPreviewService.PreviewResponse preview(Long id, Long userId) {
        AtsCheckResult result = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS 检查记录不存在"));
        ResumeVersion version = ownedVersion(result.getResumeVersionId(), userId);
        JobDescription job = jobRepository.findByIdAndUserId(result.getJobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
        return aiStarter.preview(result, version, job);
    }

    private ResumeVersion ownedVersion(Long versionId, Long userId) {
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (version.getDeletedAt() != null) {
            // 已归档的版本资源本身存在,与"不存在"(404)区分:归档是可逆状态,用 409 提示先恢复
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再发起检查");
        }
        if (resumeRepository.findByIdAndUserId(version.getResumeId(), userId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        return version;
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
        return AtsResultStateService.longValueOf(value);
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
