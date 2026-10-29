package com.intelligentresume.resume.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.intelligentresume.resume.service.JsonResumeText.firstText;
import static com.intelligentresume.resume.service.JsonResumeText.joinNonBlank;
import static com.intelligentresume.resume.service.JsonResumeText.mapValue;
import static com.intelligentresume.resume.service.JsonResumeText.putDate;
import static com.intelligentresume.resume.service.JsonResumeText.putText;
import static com.intelligentresume.resume.service.JsonResumeText.splitKeywords;
import static com.intelligentresume.resume.service.JsonResumeText.textList;

/**
 * 平台章节 → JSON Resume 标准对象/条目的逐字段映射。
 *
 * <p>单独成类的原因：这是"格式互操作"的全部知识密度所在（哪个平台的字段叫什么、
 * 标准里对应的 key 是哪个），与编排、序列化、下载头无关。混在一起会既难测也难改。
 *
 * <p>通用规则：**只做重命名与形状转换，不做语义加工**——不补空值、不裁剪条目、
 * 不把自由文本时间转成结构化日期。导出件必须能反过来说明"用户本来写了什么"。
 */
@Component
public class JsonResumeFieldMapper {

    /** {@code basics} 映射（含 {@code links → profiles} 与 {@code location} 归一）。 */
    public Map<String, Object> basics(Map<String, Object> source, List<Map<String, Object>> links) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(source, "name"));
        putText(out, "label", firstText(source, "label", "title"));
        putText(out, "email", firstText(source, "email"));
        putText(out, "phone", firstText(source, "phone"));
        putText(out, "url", firstText(source, "url", "website"));
        putText(out, "summary", firstText(source, "summary"));

        Map<String, Object> location = location(source.get("location"));
        if (!location.isEmpty()) out.put("location", location);

        List<Map<String, Object>> profiles = profiles(links);
        if (!profiles.isEmpty()) out.put("profiles", profiles);
        return out;
    }

    private Map<String, Object> location(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?>) {
            Map<String, Object> source = mapValue(value);
            putText(out, "address", firstText(source, "address"));
            putText(out, "postalCode", firstText(source, "postalCode", "zip"));
            putText(out, "city", firstText(source, "city"));
            putText(out, "region", firstText(source, "region", "province", "state"));
            putText(out, "countryCode", firstText(source, "countryCode", "country"));
            return out;
        }
        // 平台允许 location 直接是字符串（城市名），标准里必须落在 city
        putText(out, "city", value == null ? null : String.valueOf(value));
        return out;
    }

    /** 平台的 {@code links} 只有 label/url，标准里对应 {@code basics.profiles}。 */
    private List<Map<String, Object>> profiles(List<Map<String, Object>> links) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> link : links) {
            String url = firstText(link, "url");
            if (url == null) continue;
            String label = firstText(link, "label", "network", "name");
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("network", label == null ? "Link" : label);
            putText(profile, "username", firstText(link, "username", "handle"));
            profile.put("url", url);
            out.add(profile);
        }
        return out;
    }

    public Map<String, Object> work(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(item, "company", "organization", "name"));
        putText(out, "position", firstText(item, "position", "role", "title"));
        putText(out, "url", firstText(item, "url"));
        putText(out, "location", locationText(item.get("location")));
        putDates(out, item);
        putText(out, "summary", firstText(item, "description", "summary"));
        putHighlights(out, item.get("highlights"));
        // period 属条目级附加字段：官方 schema 对条目是 additionalProperties: true，原样保留不造假
        putText(out, "period", firstText(item, "period"));
        return out;
    }

    public Map<String, Object> volunteer(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "organization", firstText(item, "organization", "company", "name"));
        putText(out, "position", firstText(item, "position", "role", "title"));
        putText(out, "url", firstText(item, "url"));
        putDates(out, item);
        putText(out, "summary", firstText(item, "description", "summary"));
        putHighlights(out, item.get("highlights"));
        putText(out, "period", firstText(item, "period"));
        return out;
    }

    public Map<String, Object> education(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "institution", firstText(item, "institution", "school", "name"));
        putText(out, "area", firstText(item, "area", "major", "field"));
        putText(out, "studyType", firstText(item, "studyType", "degree"));
        putText(out, "score", firstText(item, "score", "gpa"));
        putText(out, "url", firstText(item, "url"));
        putDates(out, item);
        putText(out, "period", firstText(item, "period"));
        return out;
    }

    public Map<String, Object> award(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "title", firstText(item, "title", "name"));
        putText(out, "awarder", firstText(item, "awarder", "issuer", "organization"));
        putText(out, "summary", firstText(item, "summary", "description"));
        putDate(out, "date", firstText(item, "date"));
        return out;
    }

    public Map<String, Object> certificate(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(item, "name", "title"));
        putText(out, "issuer", firstText(item, "issuer", "organization"));
        putText(out, "url", firstText(item, "url"));
        putDate(out, "date", firstText(item, "date", "issueDate"));
        return out;
    }

    public Map<String, Object> publication(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(item, "name", "title"));
        putText(out, "publisher", firstText(item, "publisher", "journal"));
        putText(out, "summary", firstText(item, "summary", "description", "abstract"));
        putText(out, "url", firstText(item, "url"));
        putDate(out, "releaseDate", firstText(item, "releaseDate", "date"));
        return out;
    }

    public Map<String, Object> skill(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(item, "name", "skill"));
        putText(out, "level", firstText(item, "level", "proficiency"));
        List<String> keywords = textList(item.get("keywords"));
        // 平台里常见「技能名 + 逗号分隔关键词」的写法，拆成标准的关键词数组
        if (keywords.isEmpty()) keywords = splitKeywords(firstText(item, "keywords"));
        if (!keywords.isEmpty()) out.put("keywords", keywords);
        return out;
    }

    public Map<String, Object> language(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "language", firstText(item, "language", "name"));
        putText(out, "fluency", firstText(item, "fluency", "level"));
        return out;
    }

    public Map<String, Object> project(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "name", firstText(item, "name", "title"));
        putText(out, "description", firstText(item, "description", "summary"));
        putText(out, "entity", firstText(item, "entity", "company", "organization"));
        putText(out, "url", firstText(item, "url"));
        putDates(out, item);
        putHighlights(out, item.get("highlights"));
        List<String> keywords = textList(item.get("technologies"));
        if (keywords.isEmpty()) keywords = splitKeywords(firstText(item, "technologies", "keywords"));
        if (!keywords.isEmpty()) out.put("keywords", keywords);
        String role = firstText(item, "role", "position");
        if (role != null) out.put("roles", List.of(role));
        putText(out, "period", firstText(item, "period"));
        return out;
    }

    private void putDates(Map<String, Object> out, Map<String, Object> item) {
        putDate(out, "startDate", firstText(item, "startDate"));
        putDate(out, "endDate", firstText(item, "endDate"));
    }

    private void putHighlights(Map<String, Object> out, Object raw) {
        List<String> highlights = textList(raw);
        if (!highlights.isEmpty()) out.put("highlights", highlights);
    }

    private String locationText(Object raw) {
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> location = mapValue(raw);
            return joinNonBlank(List.of(
                    firstText(location, "city"), firstText(location, "region")), ", ");
        }
        return raw == null ? null : String.valueOf(raw);
    }
}
