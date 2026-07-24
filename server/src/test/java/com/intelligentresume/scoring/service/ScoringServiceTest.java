package com.intelligentresume.scoring.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.jobdescription.service.JdKeywordParser;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.scoring.domain.MatchResult;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.repository.MatchResultRepository;
import com.intelligentresume.scoring.rule.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ScoringService 单元测试（Mockito）。
 * 覆盖：稳定性、rule_version、disclaimer、自动解析、空 JD、空简历、跨用户、无效输入。
 */
@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock private ResumeVersionRepository versionRepository;
    @Mock private ResumeRepository resumeRepository;
    @Mock private JobDescriptionRepository jdRepository;
    @Mock private MatchResultRepository matchResultRepository;

    private ScoringService service;
    private final JdKeywordParser jdKeywordParser = new JdKeywordParser(
            List.of("Java", "Spring Boot", "MySQL", "Redis", "Docker", "Kubernetes"),
            List.of("本科", "硕士", "博士"),
            "(\\d+)\\s*年(以上)?(?:经验|工作)");

    private static final Long USER_ID = 100L;
    private static final Long VERSION_ID = 1L;
    private static final Long JD_ID = 1L;

    @BeforeEach
    void setUp() {
        Normalizer normalizer = new Normalizer(Map.of(
                "spring", List.of("spring", "spring boot", "spring cloud"),
                "java", List.of("java", "jdk", "openjdk"),
                "mysql", List.of("mysql", "mariadb"),
                "k8s", List.of("k8s", "kubernetes"),
                "redis", List.of("redis")
        ));
        KeywordRule keywordRule = new KeywordRule(normalizer);
        SkillRule skillRule = new SkillRule(keywordRule);
        ExperienceRule experienceRule = new ExperienceRule();
        RuleRegistry ruleRegistry = new RuleRegistry(keywordRule, skillRule, experienceRule, 0.4, 0.4, 0.2);
        ResumeKeywordExtractor extractor = new ResumeKeywordExtractor(normalizer);

        service = new ScoringService(versionRepository, resumeRepository, jdRepository,
                matchResultRepository, jdKeywordParser, extractor, ruleRegistry);
        ReflectionTestUtils.setField(service, "ruleVersion", "v1.0.0");
        ReflectionTestUtils.setField(service, "disclaimer", "本结果为 JD 规则覆盖度，非企业 ATS 结果、非录用概率");
    }

    private ResumeVersion buildVersion(Map<String, Object> resumeJson) {
        ResumeVersion v = new ResumeVersion();
        v.setId(VERSION_ID);
        v.setResumeId(10L);
        v.setVersionNo(1);
        v.setResumeJson(resumeJson);
        v.setCreatedBy(USER_ID);
        return v;
    }

    private JobDescription buildJd(String jdText, Map<String, Object> parsed) {
        JobDescription jd = new JobDescription();
        jd.setId(JD_ID);
        jd.setUserId(USER_ID);
        jd.setTitle("Java后端");
        jd.setJdText(jdText);
        jd.setParsedKeywordsJson(parsed);
        return jd;
    }

    private void setupHappyPath(ResumeVersion version, JobDescription jd) {
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        Resume resume = new Resume();
        resume.setId(10L);
        resume.setUserId(USER_ID);
        when(resumeRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.of(resume));
        when(jdRepository.findByIdAndUserId(JD_ID, USER_ID)).thenReturn(Optional.of(jd));
        when(matchResultRepository.save(any())).thenAnswer(inv -> {
            MatchResult mr = inv.getArgument(0);
            mr.setId(1L);
            return mr;
        });
    }

    @Test
    @DisplayName("正常路径: 同输入产生稳定分数与解释")
    void score_stableForSameInput() {
        Map<String, Object> resumeJson = Map.of(
                "skills", List.of(Map.of("name", "Java", "keywords", List.of("Spring Boot", "MySQL"))),
                "work", List.of(Map.of("position", "Java开发", "highlights", List.of("负责Redis缓存优化")))
        );
        ResumeVersion version = buildVersion(resumeJson);
        JobDescription jd = buildJd("需要Java、Spring Boot、MySQL经验",
                Map.of("keywords", List.of("Java", "Spring Boot", "MySQL"), "requirements", List.of()));
        setupHappyPath(version, jd);

        MatchRequest req = new MatchRequest(VERSION_ID, JD_ID);
        MatchResponse resp1 = service.score(req, USER_ID);
        MatchResponse resp2 = service.score(req, USER_ID);

        assertEquals(resp1.totalScore(), resp2.totalScore());
        assertEquals(resp1.keywordScore(), resp2.keywordScore());
        assertEquals(resp1.explanation().matched(), resp2.explanation().matched());
    }

    @Test
    @DisplayName("正常路径: 写入 match_result 含 rule_version")
    void score_persistsRuleVersion() {
        ResumeVersion version = buildVersion(Map.of("skills", List.of()));
        JobDescription jd = buildJd("Java开发", Map.of("keywords", List.of("Java"), "requirements", List.of()));
        setupHappyPath(version, jd);

        service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID);

        ArgumentCaptor<MatchResult> captor = ArgumentCaptor.forClass(MatchResult.class);
        verify(matchResultRepository).save(captor.capture());
        assertEquals("v1.0.0", captor.getValue().getRuleVersion());
    }

    @Test
    @DisplayName("正常路径: explanation.disclaimer 与配置一致")
    void score_disclaimerMatchesConfig() {
        ResumeVersion version = buildVersion(Map.of("skills", List.of()));
        JobDescription jd = buildJd("Java", Map.of("keywords", List.of("Java"), "requirements", List.of()));
        setupHappyPath(version, jd);

        MatchResponse resp = service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID);

        assertEquals("本结果为 JD 规则覆盖度，非企业 ATS 结果、非录用概率",
                resp.explanation().disclaimer());
    }

    @Test
    @DisplayName("边界路径: JD 无解析结果时自动解析并继续")
    void score_jdNotParsed_autoParse() {
        ResumeVersion version = buildVersion(Map.of(
                "skills", List.of(Map.of("name", "Java"))));
        // parsed_keywords_json 为 null → 自动解析 jdText
        JobDescription jd = buildJd("需要Java、MySQL经验", null);
        setupHappyPath(version, jd);

        MatchResponse resp = service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID);

        assertNotNull(resp.totalScore());
        // 自动解析应该找到 Java 和 MySQL
        verify(matchResultRepository).save(any());
    }

    @Test
    @DisplayName("边界路径: 空 JD + 完整简历,total≈100")
    void score_emptyJd_fullResume() {
        Map<String, Object> resumeJson = Map.of(
                "skills", List.of(Map.of("name", "Java", "keywords", List.of("Spring", "MySQL"))),
                "work", List.of(Map.of("position", "开发"))
        );
        ResumeVersion version = buildVersion(resumeJson);
        // 空 JD：无关键词
        JobDescription jd = buildJd("", Map.of("keywords", List.of(), "requirements", List.of()));
        setupHappyPath(version, jd);

        MatchResponse resp = service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(resp.totalScore()));
        assertTrue(resp.explanation().missing().isEmpty());
    }

    @Test
    @DisplayName("边界路径: 空简历 + 完整 JD,total=低分且 missing 包含全部关键词")
    void score_emptyResume_fullJd() {
        ResumeVersion version = buildVersion(Map.of()); // 空简历
        JobDescription jd = buildJd("需要Java、Spring Boot、MySQL、Redis经验，5年以上经验",
                Map.of("keywords", List.of("Java", "Spring Boot", "MySQL", "Redis"),
                        "requirements", List.of("5年以上经验")));
        setupHappyPath(version, jd);

        MatchResponse resp = service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID);

        assertTrue(resp.totalScore().compareTo(BigDecimal.valueOf(50)) < 0);
        assertEquals(4, resp.explanation().missing().size());
    }

    @Test
    @DisplayName("失败路径: 跨用户评分返回 NOT_FOUND")
    void score_crossUser_notFound() {
        ResumeVersion version = buildVersion(Map.of());
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(resumeRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.score(new MatchRequest(VERSION_ID, JD_ID), USER_ID));
        assertEquals(ErrorCode.NOT_FOUND.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: 简历版本或 JD 不存在返回 NOT_FOUND")
    void score_invalidInputs_notFound() {
        when(versionRepository.findById(999L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.score(new MatchRequest(999L, JD_ID), USER_ID));
        assertEquals(ErrorCode.NOT_FOUND.code(), ex.getErrorCode().code());
    }
}
