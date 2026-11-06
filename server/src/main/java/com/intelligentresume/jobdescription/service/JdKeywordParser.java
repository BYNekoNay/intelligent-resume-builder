package com.intelligentresume.jobdescription.service;

import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 JD 关键词解析器。严禁调用任何 LLM。
 *
 * <p>用大白话说：用户把一份招聘 JD（岗位描述）粘进来，本类从这段文字里机械地抽出三样东西——
 * 岗位名（role）、技术关键词（keywords）、硬性要求（requirements，经验年限+学历）。
 * "确定性"= 同一段文字永远抽出同样的结果，这样后续算"简历对 JD 的关键词覆盖度"时
 * 分数才可比。换成 AI 抽取的话每次结果都不同，覆盖度就失去意义了。
 *
 * <p>在链路里的位置：{@code JobDescriptionService.parse()} 调它 → 结果带
 * {@link JdParserRuleVersion 规则版本}落库 → 下游 ScoringService/选材快照读取。
 * 它自己是无状态纯函数，不碰数据库。
 *
 * <p>改坏它用户会看到什么：词典/正则改动会让同一份 JD 抽出不同关键词，
 * 老数据不会重算——若不同步升级 {@link JdParserRuleVersion#CURRENT}，
 * 新旧结果在库里无法区分，覆盖度对比就不可信了。
 *
 * <p>规则:
 * <ol>
 *     <li>关键词:在词典中大小写不敏感匹配,保留词典原始大小写,去重保持首次出现顺序</li>
 *     <li>经验:正则匹配 "X 年(以上)?(经验|工作)"</li>
 *     <li>教育:教育关键词词典命中</li>
 *     <li>role:取 jdText 第一行非空内容,>60 字符截断</li>
 *     <li>空或纯空白输入、或长度短于 {@code app.job.jd-text.min-length}:返回 role=null, keywords=[], requirements=[]</li>
 * </ol>
 *
 * <p>实现要点（与上面的口径说明有出入处以代码为准）：
 * <ul>
 *   <li>关键词是<b>子串匹配</b>，没有词边界：词典里的 "Java" 也会命中 "JavaScript"，
 *       词典项越短误报越多，扩充词典时优先用带空格/带版本的写法；</li>
 *   <li>关键词输出顺序实际是<b>词典声明顺序</b>（遍历词典命中即加入 LinkedHashSet），
 *       不是"文中首次出现顺序"；需要按出现位置排序必须另写一遍匹配；</li>
 *   <li>关键词/经验去重都不敏感，但教育关键词用 {@code jdText.contains(edu)} 是
 *       <b>大小写敏感</b>的，词典里的 "Bachelor"/"Master" 只能匹配到原样大小写；</li>
 *   <li>短文本不报错，返回三个空值——「招 Java 工程师」这类 JD 是合法输入，只是信息量不足；</li>
 *   <li>解析结果会带规则版本落库；改词典/正则后同一份 JD 会抽出不同结果，
 *       需评估是否同步 {@link JdParserRuleVersion#CURRENT}，否则新旧结果无法区分。</li>
 * </ul>
 */
@Component
public class JdKeywordParser {

    /** 与 `app.job.jd-text.min-length` 的 yml 默认值保持一致。 */
    static final int DEFAULT_MIN_LENGTH = 20;

    private final List<String> keywordDictionary;
    private final List<String> educationKeywords;
    private final Pattern experiencePattern;
    private final int minLength;

    /** 生产用构造：词典与正则来自 {@link JdParserProperties}。多构造器时 Spring 需要显式 @Autowired。 */
    @Autowired
    public JdKeywordParser(JdParserProperties properties,
                           @Value("${app.job.jd-text.min-length:20}") int minLength) {
        this(properties.getKeywordDictionary(), properties.getEducationKeywords(),
                properties.getExperiencePattern(), minLength);
    }

    /** Constructor kept explicit for deterministic unit tests. */
    public JdKeywordParser(List<String> keywordDictionary, List<String> educationKeywords, String experienceRegex) {
        this(keywordDictionary, educationKeywords, experienceRegex, DEFAULT_MIN_LENGTH);
    }

    public JdKeywordParser(List<String> keywordDictionary, List<String> educationKeywords,
                           String experienceRegex, int minLength) {
        this.keywordDictionary = keywordDictionary;
        this.educationKeywords = educationKeywords;
        this.experiencePattern = Pattern.compile(experienceRegex);
        this.minLength = minLength;
    }

    /**
     * 解析入口。步骤：①空/过短文本直接返回三空值（不报错——短 JD 是合法输入，只是没东西可抽）；
     * ②取首行非空文本当岗位名；③拿词典逐项在全文里找（子串包含）；④正则抽经验年限 + 词典抽学历。
     * 三个结果互相独立，任一为空不影响其他两个。
     * 正则只编译一次（构造时），因为每次 parse 都 compile 会在批量场景放大开销。
     */
    public ParsedKeywordsResponse parse(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            return new ParsedKeywordsResponse(null, List.of(), List.of());
        }
        // T05 口径（2026-10-01 决策 D3 实施）：短于 `app.job.jd-text.min-length` 的文本视为**无有效内容** ——
        // 允许解析，但 role/keywords/requirements 全空；**不**拒绝入参（「招 Java 工程师」这类短 JD 是合法输入，
        // 只是信息量不足以抽取关键词）。此前该配置声明却无消费点，改它不生效。
        if (jdText.trim().length() < minLength) {
            return new ParsedKeywordsResponse(null, List.of(), List.of());
        }

        String role = extractRole(jdText);
        List<String> keywords = extractKeywords(jdText);
        List<String> requirements = extractRequirements(jdText);

        return new ParsedKeywordsResponse(role, keywords, requirements);
    }

    /** 取首行非空文本作为岗位名；超 60 字符硬截断，不按词边界回退。 */
    private String extractRole(String jdText) {
        String[] lines = jdText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
            }
        }
        return null;
    }

    /** 遍历词典做大小写不敏感子串命中，输出去重且按词典顺序；返回的是词典原样大小写。 */
    private List<String> extractKeywords(String jdText) {
        String lower = jdText.toLowerCase();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String keyword : keywordDictionary) {
            if (lower.contains(keyword.toLowerCase())) {
                result.add(keyword);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 需求项 = 经验年限（正则全量命中，去掉内部空白）+ 教育关键词（原样大小写、按词典顺序）。
     * 两类共用同一个去重集合，先经验后教育，所以输出顺序固定为"经验在前"。
     */
    private List<String> extractRequirements(String jdText) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        // 经验年限
        Matcher matcher = experiencePattern.matcher(jdText);
        while (matcher.find()) {
            result.add(matcher.group().replaceAll("\\s+", ""));
        }

        // 教育关键词
        for (String edu : educationKeywords) {
            if (jdText.contains(edu)) {
                result.add(edu);
            }
        }

        return new ArrayList<>(result);
    }
}
