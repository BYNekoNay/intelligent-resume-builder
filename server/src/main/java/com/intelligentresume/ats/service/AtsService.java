package com.intelligentresume.ats.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.dto.AtsCheckResponse;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.service.ScoringService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AtsService {
    private final ScoringService scoringService;
    private final ResumeVersionRepository versionRepository;
    private final AtsCheckResultRepository repository;

    public AtsService(ScoringService scoringService, ResumeVersionRepository versionRepository,
                      AtsCheckResultRepository repository) {
        this.scoringService = scoringService;
        this.versionRepository = versionRepository;
        this.repository = repository;
    }

    @Transactional
    public AtsCheckResponse check(AtsCheckRequest request, Long userId) {
        MatchResponse match = scoringService.score(
                new MatchRequest(request.resumeVersionId(), request.jobDescriptionId()), userId);
        ResumeVersion version = versionRepository.findById(request.resumeVersionId()).orElseThrow();
        int structure = structureScore(version.getResumeJson());

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("structure", structure);
        checks.put("keywordCoverage", match.keywordScore());
        checks.put("skillCoverage", match.skillScore());
        checks.put("experienceCoverage", match.experienceScore());

        List<String> passed = new ArrayList<>();
        if (structure >= 75) passed.add("简历核心结构完整");
        if (atLeast(match.keywordScore(), 70)) passed.add("JD 关键词覆盖良好");
        if (atLeast(match.skillScore(), 70)) passed.add("技能章节覆盖良好");

        List<String> risks = new ArrayList<>();
        if (structure < 75) risks.add("简历缺少姓名、经历、技能或教育等核心结构");
        if (!match.explanation().missing().isEmpty()) {
            risks.add("缺少 JD 关键词: " + String.join(", ", match.explanation().missing()));
        }
        List<String> priorities = match.explanation().suggestions();

        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("checks", checks);
        persisted.put("passedChecks", passed);
        persisted.put("risks", risks);
        persisted.put("priorities", priorities);
        persisted.put("disclaimer", match.explanation().disclaimer());

        AtsCheckResult entity = new AtsCheckResult();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setTotalScore(match.totalScore());
        entity.setResultJson(persisted);
        repository.save(entity);

        return new AtsCheckResponse(entity.getId(), match.totalScore(), checks, passed, risks, priorities,
                match.explanation().disclaimer());
    }

    private boolean atLeast(BigDecimal score, int threshold) {
        return score.compareTo(BigDecimal.valueOf(threshold)) >= 0;
    }

    private int structureScore(Map<String, Object> resume) {
        if (resume == null) return 0;
        int score = 0;
        Object basicsValue = resume.get("basics");
        if (basicsValue instanceof Map<?, ?> basics && basics.get("name") instanceof String name && !name.isBlank()) score += 25;
        if (nonEmptyList(resume.get("work"))) score += 25;
        if (nonEmptyList(resume.get("skills"))) score += 25;
        if (nonEmptyList(resume.get("education")) || nonEmptyList(resume.get("projects"))) score += 25;
        return score;
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> values && !values.isEmpty();
    }
}
