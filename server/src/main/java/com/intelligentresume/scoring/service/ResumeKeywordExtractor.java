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
 *   <li>work[*].position, work[*].highlights[*]</li>
 *   <li>projects[*].name, projects[*].description, projects[*].highlights[*]</li>
 *   <li>skills[*].name, skills[*].keywords[*]</li>
 *   <li>certificates[*].name</li>
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
                if (item instanceof Map) {
                    Map<String, Object> s = (Map<String, Object>) item;
                    addText(s.get("name"), raw);
                    Object keywords = s.get("keywords");
                    if (keywords instanceof List) {
                        for (Object kw : (List<Object>) keywords) {
                            addText(kw, raw);
                        }
                    }
                }
            }
        }
    }

    private void addText(Object value, Set<String> raw) {
        if (value instanceof String s && !s.isBlank()) {
            for (String token : normalizer.tokenize(s)) {
                String cleaned = token.toLowerCase()
                        .replaceAll("[^\\p{L}\\p{N}\\s\\-]", "")
                        .replaceAll("\\s+", " ").trim();
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
