package com.intelligentresume.scoring.service;

import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.scoring.rule.Normalizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从简历版本(已 normalized JSON Resume)中抽取 tokens。
 *
 * <p>来源字段:
 * <ul>
 *     <li>basics.name / summary(若有)</li>
 *     <li>work[].company / position / highlights[].text</li>
 *     <li>education[].school / degree</li>
 *     <li>skills[].name</li>
 *     <li>projects[].name / description</li>
 * </ul>
 */
@Service
public class ResumeKeywordExtractor {

    private final Normalizer normalizer;

    public ResumeKeywordExtractor(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    public Set<String> extract(ResumeVersion version) {
        if (version == null || version.getResumeJson() == null) return Set.of();
        return extract(version.getResumeJson());
    }

    public Set<String> extract(Map<String, Object> resumeJson) {
        Set<String> out = new LinkedHashSet<>();
        Object basics = resumeJson.get("basics");
        if (basics instanceof Map<?, ?> b) {
            appendText(out, stringValue(b, "name"));
            appendText(out, stringValue(b, "summary"));
        }
        iterateArray(out, resumeJson.get("work"), item -> {
            appendText(out, stringValue(item, "company"));
            appendText(out, stringValue(item, "position"));
            Object highlights = item.get("highlights");
            if (highlights instanceof List<?> hl) {
                for (Object h : hl) appendText(out, stringValue(h, "text"));
            }
        });
        iterateArray(out, resumeJson.get("education"), item -> {
            appendText(out, stringValue(item, "school"));
            appendText(out, stringValue(item, "degree"));
        });
        iterateArray(out, resumeJson.get("skills"), item -> appendText(out, stringValue(item, "name")));
        iterateArray(out, resumeJson.get("projects"), item -> {
            appendText(out, stringValue(item, "name"));
            appendText(out, stringValue(item, "description"));
        });
        return out;
    }

    private void iterateArray(Set<String> out, Object root, java.util.function.Consumer<Map<String, Object>> consumer) {
        if (!(root instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> safe = (Map<String, Object>) m;
                consumer.accept(safe);
            }
        }
    }

    private String stringValue(Object obj, String key) {
        if (!(obj instanceof Map<?, ?> map)) return null;
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private void appendText(Set<String> out, String text) {
        if (text == null) return;
        List<String> tokens = normalizer.tokenize(text);
        if (tokens.isEmpty() && !text.isBlank()) {
            out.add(normalizer.canonical(text));
        } else {
            out.addAll(tokens);
        }
    }
}

