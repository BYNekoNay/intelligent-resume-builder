package com.intelligentresume.scoring.service;

import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.scoring.rule.Normalizer;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 简历关键词抽取器。从 ResumeVersion.resumeJson 中抽取归一化 token。
 *
 * <p>抽取来源（只取一级数组的可见字段）：
 * <ul>
 *   <li>basics.label, basics.summary</li>
 *   <li>work[*].position, work[*].company, work[*].highlights[*]</li>
 *   <li>projects[*].name, projects[*].description, projects[*].highlights[*]</li>
 *   <li>skills[*].name, skills[*].items, skills[*].keywords（items 为规范字段）</li>
 *   <li>certificates[*].name</li>
 * </ul>
 *
 * <p>每个文本字段同时产出**单词**与**连续词组**（见 {@link Normalizer#tokenizeWithPhrases}），
 * 以支持 {@code Spring Boot} 这类含空格的多词 JD 关键词。
 *
 * <p>要点：
 * <ul>
 *   <li><b>只抽"可见的专业内容"</b>：basics 只取 label/summary，姓名、邮箱、电话一律不参与，避免联系方式混进关键词。</li>
 *   <li><b>raw 与 normalized 的区别</b>：raw 只做小写去标点（{@code Normalizer.clean}），normalized 再多一步同义词归一；
 *       前者用于判断"是否逐字命中"，后者用于判断"是否命中"。</li>
 *   <li><b>skills 的规范字段是 {@code items}</b>，{@code keywords} 只是历史别名，两者都读；新增结构时以 items 为准。</li>
 *   <li><b>词组会放大 token 数量</b>（每个拉丁词向后最多再拼 5 个词），这是精度换体积的有意选择，不要在热路径上无限加长。</li>
 *   <li>抽取是纯函数式的读操作，不改 {@code resumeJson}；但每次调用都会重新遍历全量 JSON，别在循环里反复调。</li>
 * </ul>
 */
@Service
public class ResumeKeywordExtractor {

    private final Normalizer normalizer;

    public ResumeKeywordExtractor(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 抽取全量归一化 token。
     */
    public Set<String> extract(ResumeVersion version) {
        return extractFromJson(version.getResumeJson(), false);
    }

    /**
     * 抽取 skills 子集归一化 token。
     */
    public Set<String> extractSkillTokens(ResumeVersion version) {
        return extractFromJson(version.getResumeJson(), true);
    }

    /**
     * 抽取原始 token（小写去标点，未做同义词归一）。
     */
    public Set<String> extractRaw(ResumeVersion version) {
        Set<String> raw = new LinkedHashSet<>();
        Map<String, Object> json = version.getResumeJson();
        if (json == null) {
            return raw;
        }
        collectRawText(json, raw, false);
        return raw;
    }

    /**
     * 抽取 skills 子集原始 token。
     */
    public Set<String> extractSkillRaw(ResumeVersion version) {
        Set<String> raw = new LinkedHashSet<>();
        Map<String, Object> json = version.getResumeJson();
        if (json == null) {
            return raw;
        }
        collectRawText(json, raw, true);
        return raw;
    }

    // ---- 内部实现 ----

    private Set<String> extractFromJson(Map<String, Object> json, boolean skillsOnly) {
        Set<String> tokens = new LinkedHashSet<>();
        if (json == null) {
            return tokens;
        }
        Set<String> raw = new LinkedHashSet<>();
        collectRawText(json, raw, skillsOnly);
        for (String r : raw) {
            String normalized = normalizer.normalize(r);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private void collectRawText(Map<String, Object> json, Set<String> raw, boolean skillsOnly) {
        if (!skillsOnly) {
            // basics
            Object basics = json.get("basics");
            if (basics instanceof Map) {
                Map<String, Object> b = (Map<String, Object>) basics;
                addText(b.get("label"), raw);
                addText(b.get("summary"), raw);
            }

            // work
            Object work = json.get("work");
            if (work instanceof List) {
                for (Object item : (List<Object>) work) {
                    if (item instanceof Map) {
                        Map<String, Object> w = (Map<String, Object>) item;
                        addText(w.get("position"), raw);
                        addText(w.get("company"), raw);
                        addHighlights(w.get("highlights"), raw);
                    }
                }
            }

            // projects
            Object projects = json.get("projects");
            if (projects instanceof List) {
                for (Object item : (List<Object>) projects) {
                    if (item instanceof Map) {
                        Map<String, Object> p = (Map<String, Object>) item;
                        addText(p.get("name"), raw);
                        addText(p.get("description"), raw);
                        addHighlights(p.get("highlights"), raw);
                    }
                }
            }

            // certificates
            Object certs = json.get("certificates");
            if (certs instanceof List) {
                for (Object item : (List<Object>) certs) {
                    if (item instanceof Map) {
                        addText(((Map<String, Object>) item).get("name"), raw);
                    }
                }
            }
        }

        // skills（skillsOnly 时只取这部分）
        Object skills = json.get("skills");
        if (skills instanceof List) {
            for (Object item : (List<Object>) skills) {
                // 兼容 skills: ["Java", "Spring Boot"] 这类纯字符串项
                addText(item, raw);
                if (item instanceof Map) {
                    Map<String, Object> s = (Map<String, Object>) item;
                    addText(s.get("name"), raw);
                    // skills 的**规范字段是 items**（见 JobGenerationPromptBuilder 的示例结构
                    // {"name": "...", "category": "...", "items": [...]}）；keywords 为历史/别名写法。
                    // 历史上这里只读 keywords，导致技能项**永远抽不出来** →
                    // skillCoverage 恒低，并给出"建议在技能部分补充 X"这种与简历矛盾的误导建议。
                    addText(s.get("items"), raw);
                    addText(s.get("keywords"), raw);
                }
            }
        }
    }

    private void addText(Object value, Set<String> raw) {
        // 支持数组（如 skills[*].items / keywords），统一走同一套清洗规则
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                addText(item, raw);
            }
            return;
        }
        if (value instanceof String s && !s.isBlank()) {
            // 同时收录**单词**与**连续词组**：
            // JD 关键词来自配置词典，可能是含空格的多词短语（如 "Spring Boot"）；
            // 若只收录单词，"spring boot" 永远不可能等于任何 token →
            // 多词关键词即使与简历逐字相同也必然判缺失（详见 Normalizer#tokenizeWithPhrases）。
            for (String token : normalizer.tokenizeWithPhrases(s, Normalizer.MAX_PHRASE_WORDS)) {
                // 清洗规则由 Normalizer.clean 单点维护（历史上此处在三个地方各抄了一份字面量）
                String cleaned = Normalizer.clean(token);
                if (!cleaned.isEmpty()) {
                    raw.add(cleaned);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addHighlights(Object highlights, Set<String> raw) {
        if (highlights instanceof List) {
            for (Object h : (List<Object>) highlights) {
                addText(h, raw);
            }
        }
    }
}
