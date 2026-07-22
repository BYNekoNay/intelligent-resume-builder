package com.intelligentresume.ai.provider;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;

import java.util.ArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock AI Provider,固定返回符合 JSON Schema 的草稿。
 *
 * <p>输出必须包含 basics / work / education / skills / projects 顶层,且每个数组元素有
 * {@code _source} 与 {@code _pending} 字段,以便 {@link com.intelligentresume.ai.generation.validator.JobGenerationSchemaValidator} 通过。
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiProvider implements AiProvider {

    private static final Pattern MATERIAL_ID_IN_PROMPT = Pattern.compile("\\\"id\\\"\\s*:\\s*([1-9]\\d*)");

    @Override
    public String code() {
        return "mock";
    }

    @Override
    public Map<String, Object> invoke(String taskType, Map<String, Object> input) {
        if ("INLINE_OPTIMIZE".equals(taskType)) {
            return inlineOptimize(input);
        }
        if ("ACHIEVEMENT_GUIDANCE".equals(taskType)) {
            return Map.of("questions", List.of(
                    "这项工作影响了多少用户、请求或业务对象？",
                    "完成前后的时间、质量或成本有什么可核实变化？",
                    "你在其中承担了什么范围的责任？"));
        }
        if ("COMMUNICATION_GENERATE".equals(taskType)) {
            String type = String.valueOf(input.getOrDefault("type", "OPENING_MESSAGE"));
            String jobTitle = String.valueOf(input.getOrDefault("jobTitle", "目标岗位"));
            return Map.of("draft", "您好，我希望应聘" + jobTitle + "。以下内容基于我已确认的简历事实整理，请您查阅。类型：" + type);
        }
        if ("MATERIAL_RESUME_GENERATION".equals(taskType)) {
            String raw = String.valueOf(input.getOrDefault("rawMaterialText", "")).trim();
            List<String> skills = List.of("Java", "Spring Boot", "MySQL", "Redis").stream().filter(raw::contains).toList();
            Map<String, Object> basics = Map.of("summary", raw, "_source", "rawMaterialText", "_pending", false);
            Map<String, Object> draft = Map.of("basics", basics, "work", List.of(), "projects", List.of(),
                    "education", List.of(), "skills", skills, "certificates", List.of(), "languages", List.of());
            return Map.of("generatedResumeJson", draft, "suggestions", List.of("建议补充工作时间范围、项目结果指标和教育经历。"));
        }
        return generateResumeFromCareerMaterials(input);
    }

    private Map<String, Object> inlineOptimize(Map<String, Object> input) {
        String original = String.valueOf(input.getOrDefault("content", "")).trim();
        String normalized = original.replace("和", "与");
        if (!normalized.endsWith("。") && !normalized.endsWith("！") && !normalized.endsWith("？")) {
            normalized += "。";
        }
        return Map.of("candidates", List.of(
                Map.of("content", normalized, "suggestion", "统一连接词和标点，使表达更正式。"),
                Map.of("content", "工作内容：" + normalized, "suggestion", "增加信息层级提示，便于招聘者快速浏览。"),
                Map.of("content", "核心职责：" + normalized, "suggestion", "突出职责属性，不补充原文之外的事实。")
        ));
    }

    private Map<String, Object> generateResumeFromCareerMaterials(Map<String, Object> input) {
        List<Map<String, Object>> work = new ArrayList<>();
        List<Map<String, Object>> projects = new ArrayList<>();
        List<Map<String, Object>> education = new ArrayList<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> certificates = new ArrayList<>();

        Object rawMaterials = input.get("materials");
        if (rawMaterials instanceof List<?> materials) {
            materials.stream()
                    .filter(CareerMaterial.class::isInstance)
                    .map(CareerMaterial.class::cast)
                    .forEach(material -> addMaterial(material, work, projects, education, skills, certificates));
            materials.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .forEach(material -> addMaterialMap(material, projects));
        }
        if (work.isEmpty() && projects.isEmpty() && education.isEmpty() && skills.isEmpty()) {
            addPromptBackedProject(input, projects);
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", Map.of());
        draft.put("work", work);
        draft.put("education", education);
        draft.put("skills", skills);
        draft.put("projects", projects);
        draft.put("certificates", certificates);
        return Map.of("draftResumeJson", draft);
    }

    private void addPromptBackedProject(Map<String, Object> input, List<Map<String, Object>> projects) {
        Object prompt = input.get("prompt");
        if (!(prompt instanceof String promptText)) return;
        Matcher matcher = MATERIAL_ID_IN_PROMPT.matcher(promptText);
        if (!matcher.find()) return;
        projects.add(new LinkedHashMap<>(Map.of(
                "name", "Mock career material project",
                "description", "Generated from the selected local validation material.",
                "_source", "material:" + matcher.group(1),
                "_pending", false)));
    }

    private void addMaterialMap(Map<?, ?> material, List<Map<String, Object>> projects) {
        Object id = material.get("id");
        if (!(id instanceof Number number)) return;
        Object type = material.get("materialType");
        if (type != null && !"PROJECT_EXPERIENCE".equals(String.valueOf(type))) return;
        Map<String, Object> item = new LinkedHashMap<>();
        Object title = material.containsKey("title") ? material.get("title") : "Mock career material project";
        Object sourceText = material.containsKey("sourceText") ? material.get("sourceText") : "Generated from selected material.";
        item.put("name", String.valueOf(title));
        item.put("description", String.valueOf(sourceText));
        item.put("_source", "material:" + number.longValue());
        item.put("_pending", false);
        projects.add(item);
    }

    private void addMaterial(CareerMaterial material,
                             List<Map<String, Object>> work,
                             List<Map<String, Object>> projects,
                             List<Map<String, Object>> education,
                             List<Map<String, Object>> skills,
                             List<Map<String, Object>> certificates) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.putAll(material.getContentJson());
        item.putIfAbsent("name", material.getTitle());
        if (material.getSourceText() != null && !material.getSourceText().isBlank()) {
            item.putIfAbsent("summary", material.getSourceText());
        }
        item.put("_source", "material:" + material.getId());
        item.put("_pending", false);

        switch (material.getMaterialType()) {
            case WORK_EXPERIENCE -> work.add(item);
            case PROJECT_EXPERIENCE -> projects.add(item);
            case EDUCATION -> education.add(item);
            case SKILL -> skills.add(item);
            case CERTIFICATE, AWARD -> certificates.add(item);
        }
    }
}
