package com.intelligentresume.scoring.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.jobdescription.service.JdKeywordParser;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.domain.MatchResult;
import com.intelligentresume.scoring.dto.Explanation;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.repository.MatchResultRepository;
import com.intelligentresume.scoring.rule.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 评分服务。纯规则计算，严禁调用 LLM。
 *
 * <p>流程：校验归属 → 读/解析 JD 关键词 → 抽取简历 token →
 * 三项规则评分 → 加权总分 → 写 match_result → 返回响应。
 */
@Service
public class ScoringService {

    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jdRepository;
    private final MatchResultRepository matchResultRepository;
    private final JdKeywordParser jdKeywordParser;
    private final ResumeKeywordExtractor keywordExtractor;
    private final RuleRegistry ruleRegistry;

    @Value("${app.scoring.rule-version:v1.0.0}")
    private String ruleVersion;

    @Value("${app.scoring.user-disclaimer:本结果为 JD 规则覆盖度，非企业 ATS 结果、非录用概率}")
    private String disclaimer;

    public ScoringService(ResumeVersionRepository versionRepository,
                          ResumeRepository resumeRepository,
                          JobDescriptionRepository jdRepository,
                          MatchResultRepository matchResultRepository,
                          JdKeywordParser jdKeywordParser,
                          ResumeKeywordExtractor keywordExtractor,
                          RuleRegistry ruleRegistry) {
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jdRepository = jdRepository;
        this.matchResultRepository = matchResultRepository;
        this.jdKeywordParser = jdKeywordParser;
        this.keywordExtractor = keywordExtractor;
        this.ruleRegistry = ruleRegistry;
    }

    /**
     * 计算并保存评分。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public MatchResponse score(MatchRequest req, Long userId) {
        // 1. 校验简历版本归属
        ResumeVersion version = versionRepository.findById(req.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        // 2. 校验 JD 归属
        JobDescription jd = jdRepository.findByIdAndUserId(req.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));

        // 3. 读取/解析 JD 关键词
        List<String> jdKeywords;
        List<String> jdRequirements;
        Map<String, Object> parsed = jd.getParsedKeywordsJson();
        if (parsed != null && parsed.containsKey("keywords")) {
            jdKeywords = toStringList(parsed.get("keywords"));
            jdRequirements = toStringList(parsed.get("requirements"));
        } else {
            // 自动解析
            ParsedKeywordsResponse parsedResult = jdKeywordParser.parse(jd.getJdText());
            jdKeywords = parsedResult.keywords();
            jdRequirements = parsedResult.requirements();
        }

        // 4. 抽取简历 token
        Set<String> resumeTokens = keywordExtractor.extract(version);
        Set<String> resumeRawTokens = keywordExtractor.extractRaw(version);
        Set<String> skillTokens = keywordExtractor.extractSkillTokens(version);
        Set<String> skillRawTokens = keywordExtractor.extractSkillRaw(version);

        // 5. 三项规则评分
        KeywordRule.RuleResult keywordResult =
                ruleRegistry.keywordRule().evaluate(jdKeywords, resumeTokens, resumeRawTokens);
        KeywordRule.RuleResult skillResult =
                ruleRegistry.skillRule().evaluate(jdKeywords, skillTokens, skillRawTokens);
        BigDecimal experienceScore =
                ruleRegistry.experienceRule().evaluate(jdRequirements, version.getResumeJson());

        // 6. 加权总分
        BigDecimal totalScore = keywordResult.score()
                .multiply(ruleRegistry.keywordWeight())
                .add(skillResult.score().multiply(ruleRegistry.skillWeight()))
                .add(experienceScore.multiply(ruleRegistry.experienceWeight()))
                .setScale(2, RoundingMode.HALF_UP);

        // 7. 构建解释
        List<String> suggestions = buildSuggestions(keywordResult, skillResult);
        Explanation explanation = new Explanation(
                keywordResult.matched(),
                keywordResult.partialMatched(),
                keywordResult.missing(),
                suggestions,
                disclaimer
        );

        // 8. 写 match_result
        MatchResult result = new MatchResult();
        result.setResumeVersionId(req.resumeVersionId());
        result.setJobDescriptionId(req.jobDescriptionId());
        result.setTotalScore(totalScore);
        result.setKeywordScore(keywordResult.score());
        result.setSkillScore(skillResult.score());
        result.setExperienceScore(experienceScore);
        result.setExplanationJson(buildExplanationJson(explanation));
        result.setRuleVersion(ruleVersion);
        matchResultRepository.save(result);

        // 9. 返回
        return new MatchResponse(
                result.getId(), totalScore,
                keywordResult.score(), skillResult.score(), experienceScore,
                explanation, ruleVersion
        );
    }

    /**
     * 查询评分结果（跨用户安全）。
     */
    @Transactional(readOnly = true)
    public MatchResult getResult(Long matchResultId, Long userId) {
        return matchResultRepository.findByIdAndUserId(matchResultId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评分结果不存在"));
    }

    // ---- helpers ----

    private List<String> buildSuggestions(KeywordRule.RuleResult keyword,
                                           KeywordRule.RuleResult skill) {
        List<String> suggestions = new ArrayList<>();
        if (!keyword.missing().isEmpty()) {
            suggestions.add("建议补充以下关键词: " + String.join(", ", keyword.missing()));
        }
        if (!skill.missing().isEmpty()) {
            suggestions.add("建议在技能部分补充: " + String.join(", ", skill.missing()));
        }
        if (suggestions.isEmpty()) {
            suggestions.add("关键词覆盖良好，可继续优化项目描述量化成果");
        }
        return suggestions;
    }

    private Map<String, Object> buildExplanationJson(Explanation explanation) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("matched", explanation.matched());
        json.put("partialMatched", explanation.partialMatched());
        json.put("missing", explanation.missing());
        json.put("suggestions", explanation.suggestions());
        json.put("disclaimer", explanation.disclaimer());
        return json;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List) {
            return ((List<Object>) value).stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .toList();
        }
        return List.of();
    }
}
