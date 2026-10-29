package com.intelligentresume.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.intelligentresume.resume.service.JsonResumeText.items;
import static com.intelligentresume.resume.service.JsonResumeText.mapValue;
import static com.intelligentresume.resume.service.JsonResumeText.maps;

/**
 * 把平台内部的 {@code resumeJson} 导出成 **JSON Resume v1.0.0** 文档。
 *
 * <p>为什么值得做：JSON Resume 是简历领域唯一被广泛实现的开源互操作格式
 * （jsonresume.org、resume-cli、Reactive Resume 等都认它）。能导出它，意味着用户的简历
 * 不被锁在本平台里——这是「数据可携带」的实质，而不是隐私声明里的一句话。
 *
 * <p><b>映射策略由官方 schema 决定，不是随意取舍</b>：
 * <ul>
 *   <li>根节点是 {@code additionalProperties: false} —— 根上**只能**出现标准键，
 *       多一个 {@code links} / {@code objective} 就会让整份文档校验失败；</li>
 *   <li>条目对象与 {@code meta} 是 {@code additionalProperties: true} —— 字段级补充信息
 *       （如自由文本 {@code period}）可以留在条目里，不必挤进 {@code meta}；</li>
 *   <li>没有标准归宿的**整块**内容（{@code objective} / {@code courses} / {@code customSections} /
 *       {@code template} / {@code layout}）放进 {@code meta.platform}：既保住内容，又不动根结构。</li>
 * </ul>
 *
 * <p>逐字段映射见 {@link JsonResumeFieldMapper}；取值与格式化见 {@link JsonResumeText}。
 */
@Component
public class JsonResumeExporter {

    /** 官方 schema 的规范化地址（写进 {@code $schema}，便于下游按版本校验）。 */
    public static final String SCHEMA_URL =
            "https://raw.githubusercontent.com/jsonresume/resume-schema/v1.0.0/schema.json";

    /** 根节点允许出现的全部键（= 官方 schema 的 properties）。 */
    public static final Set<String> STANDARD_ROOT_KEYS = Set.of(
            "$schema", "basics", "work", "volunteer", "education", "awards", "certificates",
            "publications", "skills", "languages", "interests", "references", "projects", "meta");

    /**
     * 平台侧认识的顶层键（= 简历章节注册表 + 排版配置）。
     *
     * <p>不在此列的键**不会被丢弃**，而是整体搬进 {@code meta.platform.unknownSections}——
     * 导出件的底线是「用户写过的内容一个字都不能少」，哪怕平台自己不认识它。
     */
    public static final Set<String> PLATFORM_KEYS = Set.of(
            "basics", "objective", "links", "work", "volunteering", "skills", "projects", "education",
            "courses", "certificates", "publications", "awards", "languages", "customSections",
            "template", "layout");

    private final JsonResumeFieldMapper mapper;
    private final ObjectMapper objectMapper;

    public JsonResumeExporter(JsonResumeFieldMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 纯映射：同一份 {@code resumeJson} 永远得到同一份文档（导出件要能反过来说明用户写了什么）。
     *
     * @param resumeJson 平台内部结构（JSON Resume + template/layout/objective/links/customSections 扩展）
     * @param resumeId   平台简历 id（写进 {@code meta.platform}，便于回溯来源）
     * @param versionNo  版本号（同上）
     */
    public Map<String, Object> export(Map<String, Object> resumeJson, Long resumeId, Integer versionNo) {
        Map<String, Object> json = resumeJson == null ? Map.of() : resumeJson;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$schema", SCHEMA_URL);

        Map<String, Object> basics = mapper.basics(mapValue(json.get("basics")), maps(json.get("links")));
        if (!basics.isEmpty()) out.put("basics", basics);

        // 平台章节名 → 标准章节名（volunteering→volunteer，其余同名）
        putList(out, "work", items(json.get("work"), mapper::work));
        putList(out, "volunteer", items(json.get("volunteering"), mapper::volunteer));
        putList(out, "education", items(json.get("education"), mapper::education));
        putList(out, "awards", items(json.get("awards"), mapper::award));
        putList(out, "certificates", items(json.get("certificates"), mapper::certificate));
        putList(out, "publications", items(json.get("publications"), mapper::publication));
        putList(out, "skills", items(json.get("skills"), mapper::skill));
        putList(out, "languages", items(json.get("languages"), mapper::language));
        putList(out, "projects", items(json.get("projects"), mapper::project));

        out.put("meta", meta(json, resumeId, versionNo));
        return out;
    }

    /**
     * {@code meta}：官方 schema 在此是 {@code additionalProperties: true}，
     * 平台私有内容放这里既合法、又不会与标准字段混淆。
     */
    private Map<String, Object> meta(Map<String, Object> json, Long resumeId, Integer versionNo) {
        Map<String, Object> platform = new LinkedHashMap<>();
        putIfPresent(platform, "objective", json.get("objective"));
        putIfPresent(platform, "courses", json.get("courses"));
        putIfPresent(platform, "customSections", json.get("customSections"));
        putIfPresent(platform, "template", json.get("template"));
        putIfPresent(platform, "layout", json.get("layout"));
        putUnknownSections(platform, json);
        platform.put("resumeId", resumeId);
        platform.put("versionNo", versionNo);
        platform.put("exporter", "intelligent-resume/json-resume@1");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", "v1.0.0");
        meta.put("platform", platform);
        return meta;
    }

    /** 平台不认识的顶层键整块搬走，绝不静默丢弃（历史导入残留、未来新增章节都会走这里）。 */
    private void putUnknownSections(Map<String, Object> platform, Map<String, Object> json) {
        Map<String, Object> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            if (PLATFORM_KEYS.contains(entry.getKey())) continue;
            unknown.put(entry.getKey(), entry.getValue());
        }
        if (!unknown.isEmpty()) platform.put("unknownSections", unknown);
    }

    /** 序列化成便于人读的 JSON（缩进 2 空格）：导出件同样要能被人直接看懂。 */
    public byte[] toJsonBytes(Map<String, Object> document) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "JSON Resume 序列化失败");
        }
    }

    /**
     * 下载文件名：纯 ASCII（不把中文简历标题塞进 header，避开 RFC 5987 编码在不同客户端上的差异）。
     * 文件名不带用户输入，也就没有 header 注入面。
     */
    public String fileName(Long resumeId, Integer versionNo) {
        return "resume-" + resumeId + (versionNo == null ? "" : "-v" + versionNo) + "-jsonresume.json";
    }

    private void putList(Map<String, Object> out, String key, List<Map<String, Object>> values) {
        if (!values.isEmpty()) out.put(key, values);
    }

    private void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value == null) return;
        if (value instanceof List<?> list && list.isEmpty()) return;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return;
        out.put(key, value);
    }
}
