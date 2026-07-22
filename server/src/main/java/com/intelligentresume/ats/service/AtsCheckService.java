package com.intelligentresume.ats.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.dto.AtsCheckResponse;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.service.ScoringService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AtsCheckService {
    public static final String DISCLAIMER = "本结果为规则化简历体检，不是企业 ATS 结果，也不代表录用概率";

    private final AtsCheckResultRepository repository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final ScoringService scoringService;

    public AtsCheckService(AtsCheckResultRepository repository, ResumeVersionRepository versionRepository,
                           ResumeRepository resumeRepository, ScoringService scoringService) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.scoringService = scoringService;
    }

    @Transactional
    public AtsCheckResponse check(AtsCheckRequest request, Long userId) {
        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        MatchResponse match = scoringService.score(new MatchRequest(request.resumeVersionId(), request.jobDescriptionId()), userId);

        Map<String, Object> resumeJson = version.getResumeJson();
        int textLength = resumeJson.toString().length();
        boolean hasBasics = resumeJson.get("basics") instanceof Map<?, ?>;
        boolean hasWork = resumeJson.get("work") instanceof List<?> list && !list.isEmpty();
        boolean hasSkills = resumeJson.get("skills") instanceof List<?> list && !list.isEmpty();
        List<String> risks = new ArrayList<>();
        List<String> passedChecks = new ArrayList<>();
        if (hasBasics) passedChecks.add("Basic information is present");
        if (hasWork) passedChecks.add("Work experience is present");
        if (hasSkills) passedChecks.add("Skills section is present");
        if (textLength >= 180 && textLength <= 12000) passedChecks.add("Resume length is within the recommended range");
        if (!hasBasics) risks.add("缺少基本信息模块");
        if (!hasWork) risks.add("缺少可验证的工作经历");
        if (!hasSkills) risks.add("缺少技能模块");
        if (textLength < 180) risks.add("简历内容偏短，可能缺少经历证据");
        if (textLength > 12000) risks.add("简历内容过长，建议压缩低相关信息");

        List<String> priorities = risks.stream().limit(3).toList();
        BigDecimal structureScore = BigDecimal.valueOf((hasBasics ? 34 : 0) + (hasWork ? 33 : 0) + (hasSkills ? 33 : 0));
        BigDecimal lengthScore = textLength >= 180 && textLength <= 12000 ? BigDecimal.valueOf(100) : BigDecimal.valueOf(55);
        BigDecimal total = match.totalScore().multiply(BigDecimal.valueOf(.6))
                .add(structureScore.multiply(BigDecimal.valueOf(.25)))
                .add(lengthScore.multiply(BigDecimal.valueOf(.15))).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("keywordCoverage", match.totalScore());
        checks.put("coreSkillPlacement", hasSkills ? "PASS" : "RISK");
        checks.put("experienceCompleteness", hasWork ? "PASS" : "RISK");
        checks.put("structure", structureScore);
        checks.put("length", Map.of("characters", textLength, "status", lengthScore.intValue() == 100 ? "PASS" : "RISK"));
        checks.put("ruleVersion", match.ruleVersion());

        AtsCheckResult entity = new AtsCheckResult();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setTotalScore(total);
        entity.setResultJson(Map.of("checks", checks, "passedChecks", passedChecks, "risks", risks,
                "priorities", priorities, "disclaimer", DISCLAIMER));
        AtsCheckResult saved = repository.save(entity);
        return new AtsCheckResponse(saved.getId(), total, checks, passedChecks, risks, priorities, DISCLAIMER);
    }
}
