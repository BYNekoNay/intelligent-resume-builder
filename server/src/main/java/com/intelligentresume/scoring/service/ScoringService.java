package com.intelligentresume.scoring.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.domain.MatchResult;
import com.intelligentresume.scoring.dto.Explanation;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.repository.MatchResultRepository;
import com.intelligentresume.scoring.rule.ExperienceRule;
import com.intelligentresume.scoring.rule.KeywordRule;
import com.intelligentresume.scoring.rule.Normalizer;
import com.intelligentresume.scoring.rule.RuleVersion;
import com.intelligentresume.scoring.rule.SkillRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JD 规则覆盖度评分。
 *
 * <p>三维度加权:keyword × 0.4 + skill × 0.4 + experience × 0.2。
 * 固定显示「非企业 ATS 结果、非录用概率」。
 */
@Service
public class ScoringService {

    private final MatchResultRepository repository;
    private final ResumeKeywordExtractor extractor;
    private final ResumeVersionRepository resumeVersionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final Normalizer normalizer;
    private final KeywordRule keywordRule;
    private final SkillRule skillRule;
    private final ExperienceRule experienceRule;
    private final BigDecimal weightKeyword;
    private final BigDecimal weightSkill;
    private final BigDecimal weightExperience;
    private final String disclaimer;

    public ScoringService(MatchResultRepository repository,
                          ResumeKeywordExtractor extractor,
                          ResumeVersionRepository resumeVersionRepository,
                          ResumeRepository resumeRepository,
                          JobDescriptionRepository jobDescriptionRepository,
                          Normalizer normalizer,
                          KeywordRule keywordRule,
                          SkillRule skillRule,
                          ExperienceRule experienceRule,
                          @Value("${app.scoring.weights.keyword:0.4}") BigDecimal weightKeyword,
                          @Value("${app.scoring.weights.skill:0.4}") BigDecimal weightSkill,
                          @Value("${app.scoring.weights.experience:0.2}") BigDecimal weightExperience,
                          @Value("${app.scoring.user-disclaimer:本结果为 JD 规则覆盖度,非企业 ATS 结果、非录用概率}") String disclaimer) {
        this.repository = repository;
        this.extractor = extractor;
        this.resumeVersionRepository = resumeVersionRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.normalizer = normalizer;
        this.keywordRule = keywordRule;
        this.skillRule = skillRule;
        this.experienceRule = experienceRule;
        this.weightKeyword = weightKeyword;
        this.weightSkill = weightSkill;
        this.weightExperience = weightExperience;
        this.disclaimer = disclaimer;
    }

    @Transactional
    public MatchResponse score(MatchRequest request, Long userId) {
        ResumeVersion version = findOwnedVersion(request.resumeVersionId(), userId);
        JobDescription jd = jobDescriptionRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // 抽 token
        Set<String> resumeTokens = extractor.extract(version);
        Set<String> jdTokens = normalizer.distinctTokens(jd.getJdText());

        Map<String, Object> jdMeta = new LinkedHashMap<>();
        Object parsedKeywordsJson = jd.getParsedKeywordsJson();
        if (parsedKeywordsJson instanceof Map<?, ?>) {
            jdMeta.putAll((Map<String, Object>) parsedKeywordsJson);
        }

        BigDecimal keywordScore = keywordRule.score(jdTokens, resumeTokens, jdMeta);
        BigDecimal skillScore = skillRule.score(jdTokens, resumeTokens, jdMeta);
        BigDecimal experienceScore = experienceRule.score(jdTokens, resumeTokens, jdMeta);

        BigDecimal total = keywordScore.multiply(weightKeyword)
                .add(skillScore.multiply(weightSkill))
                .add(experienceScore.multiply(weightExperience))
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("matched", keywordRule.matched(jdTokens, resumeTokens));
        explanation.put("partialMatched", keywordRule.partialMatched(jdTokens, resumeTokens));
        explanation.put("missing", keywordRule.missing(jdTokens, resumeTokens));
        explanation.put("skillsMatched", skillRule.matched(jdTokens, resumeTokens));
        explanation.put("skillsMissing", skillRule.missing(jdTokens, resumeTokens));
        explanation.put("experience", Map.of(
                "matched", experienceRule.matched(jdTokens, resumeTokens),
                "partialMatched", experienceRule.partialMatched(jdTokens, resumeTokens),
                "missing", experienceRule.missing(jdTokens, resumeTokens)
        ));
        explanation.put("suggestions", buildSuggestions(keywordRule.missing(jdTokens, resumeTokens),
                skillRule.missing(jdTokens, resumeTokens),
                experienceRule.missing(jdTokens, resumeTokens)));
        explanation.put("disclaimer", disclaimer);

        MatchResult saved = new MatchResult();
        saved.setResumeVersionId(version.getId());
        saved.setJobDescriptionId(jd.getId());
        saved.setTotalScore(total);
        saved.setKeywordScore(keywordScore);
        saved.setSkillScore(skillScore);
        saved.setExperienceScore(experienceScore);
        saved.setExplanationJson(explanation);
        saved.setRuleVersion(RuleVersion.CURRENT);
        repository.save(saved);

        return new MatchResponse(saved.getId(), total, keywordScore, skillScore, experienceScore,
                new Explanation(
                        joinList(explanation.get("matched")),
                        joinList(explanation.get("partialMatched")),
                        joinList(explanation.get("missing")),
                        joinList(explanation.get("suggestions")),
                        disclaimer
                ),
                RuleVersion.CURRENT);
    }

    public MatchResponse get(Long id, Long userId) {
        MatchResult result = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        findOwnedVersion(result.getResumeVersionId(), userId);
        jobDescriptionRepository.findByIdAndUserId(result.getJobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(result);
    }

    private ResumeVersion findOwnedVersion(Long versionId, Long userId) {
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return version;
    }

    private List<String> joinList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }

    private List<String> buildSuggestions(List<String> missingKeywords,
                                          List<String> missingSkills,
                                          List<String> missingExperience) {
        List<String> out = new ArrayList<>();
        if (!missingKeywords.isEmpty()) {
            out.add("尝试在简历中显式提及以下关键词:" + String.join("、", missingKeywords));
        }
        if (!missingSkills.isEmpty()) {
            out.add("补充以下技能证据(项目或工作经历):" + String.join("、", missingSkills));
        }
        if (!missingExperience.isEmpty()) {
            out.add("经验差距:" + String.join("; ", missingExperience));
        }
        if (out.isEmpty()) {
            out.add("已较好覆盖 JD 关键词,可以再人工润色细节");
        }
        return out;
    }

    private MatchResponse toResponse(MatchResult r) {
        Map<String, Object> explanation = r.getExplanationJson();
        List<String> matched = joinList(explanation == null ? null : explanation.get("matched"));
        List<String> partial = joinList(explanation == null ? null : explanation.get("partialMatched"));
        List<String> missing = joinList(explanation == null ? null : explanation.get("missing"));
        List<String> suggestions = joinList(explanation == null ? null : explanation.get("suggestions"));
        String disclaimerText = explanation == null ? this.disclaimer
                : String.valueOf(explanation.getOrDefault("disclaimer", this.disclaimer));
        return new MatchResponse(r.getId(), r.getTotalScore(), r.getKeywordScore(),
                r.getSkillScore(), r.getExperienceScore(),
                new Explanation(matched, partial, missing, suggestions, disclaimerText),
                r.getRuleVersion());
    }
}
