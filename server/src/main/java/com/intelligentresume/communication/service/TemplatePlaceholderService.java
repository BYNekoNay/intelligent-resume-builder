package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符服务：白名单校验 + 纯字符串替换填充。
 *
 * <p>占位符（模板里的 {@code {{name}}}，发送前替换成真实值）白名单：
 * {{candidateName}} {{jobTitle}} {{companyName}} {{topSkill}}
 * {{location}} {{email}} {{phone}}。联系方式（email/phone）由服务端从简历 basics 合并，
 * 模板填充为纯字符串替换，不经过任何模型。
 *
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：用户写模板时不想写死「尊敬的张三」，而是写 {@code {{candidateName}}}，
 *       换个候选人用同一模板照样通顺。本类负责三件事：保存模板时拦住不认识的占位符、
 *       预览时把占位符换成真实简历/JD 的值、把没值可换的占位符如实报告出来。</li>
 *   <li><b>在链路哪里</b>：被 {@code CommunicationTemplateService} 调用——saveCustom/update 时调
 *       {@link #validate} 拦非法占位符，preview 时调 {@link #fill} 出预览正文。它是模板路径唯一的
 *       占位符解析入口。</li>
 *   <li><b>改坏它用户会看到什么</b>：白名单漏放 → 用户保存 {@code {{foo}}} 模板后预览时
 *       原样显示一串 {@code {{foo}}}；替换口径不一致 → 模板里写着 {@code {{ name }}}
 *       （带空格）却永远替换不掉也不报缺失；值里的 {@code $} 未转义 → 填充结果被吃掉一段。</li>
 * </ul>
 *
 * <p>要点：
 * <ul>
 *   <li><b>名称大小写敏感</b>：白名单是原样字符串比对，{@code {{CandidateName}}} 属非法，保存时 40001。</li>
 *   <li><b>只认 {@code [a-zA-Z0-9_]} 的名字</b>：{@code {{名字}}} 不会被 Pattern 匹配，
 *       校验放行、替换时也原样保留 —— 它既不算非法也不在 missing 里，属于静默残留。</li>
 *   <li><b>缺失值不改写</b>：无值占位符原样留在正文中，只通过 {@code missingPlaceholders} 提示前端高亮。</li>
 *   <li>新增占位符要同步三处：{@code PLACEHOLDER_WHITELIST}、{@link #buildValues}、前端模板编辑器提示。</li>
 * </ul>
 */
@Component
public class TemplatePlaceholderService {

    public static final Set<String> PLACEHOLDER_WHITELIST = Set.of(
            "candidateName", "jobTitle", "companyName", "topSkill", "location", "email", "phone");

    // 唯一占位符 Pattern：提取、missing 判定、替换必须全部走它（见 fill 的口径约定）
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /**
     * 校验 bodyText 中的占位符均来自白名单，非法占位符抛 40001。
     */
    public void validate(String bodyText) {
        List<String> illegal = illegalPlaceholders(bodyText);
        if (!illegal.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "模板包含非法占位符: " + String.join(", ", illegal));
        }
    }

    /**
     * 返回 bodyText 中所有不在白名单内的占位符名。
     */
    public List<String> illegalPlaceholders(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return List.of();
        LinkedHashSet<String> illegal = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(bodyText);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!PLACEHOLDER_WHITELIST.contains(name)) {
                illegal.add(name);
            }
        }
        return new ArrayList<>(illegal);
    }

    /**
     * 从 bodyText 提取全部占位符名（去重，保持出现顺序）。
     */
    public List<String> extractPlaceholders(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(bodyText);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * 用真实简历 JSON + JD 填充白名单占位符。
     *
     * <p>步骤：① 按「简历 basics + JD」把 7 个白名单占位符的值收集成表（没值的记空串）；
     * ② 从正文提取全部占位符，值缺失的记入 missing 列表；③ 用与①②同一个 Pattern
     * 逐个匹配替换——有值换成真值，无值原样保留。②必须在③之前完成，
     * 因为 missing 列表要随结果一起返回给前端做高亮提示。
     *
     * <p>缺失值占位符原样保留，并列入返回的 missingPlaceholders。
     *
     * <p>口径约定（修复模块核实报告 P1-3）：提取（{@link #extractPlaceholders}）、
     * missing 判定与替换三条路径共用同一个 {@link #PLACEHOLDER_PATTERN}。
     * 替换必须通过 Matcher 按捕获组匹配，而不是字符串精确匹配
     * {@code replace("{{" + name + "}}", value)}——原实现中提取用的 Pattern
     * 容忍空白（{@code {{ name }}} 被判为合法占位符），而 replace 精确匹配
     * 不容忍空白，导致 {@code {{ name }}} 提取成功、missing 判为有值、
     * 却永远替换不掉且不报任何缺失。
     */
    public FillResult fill(String bodyText, Map<String, Object> resumeJson, JobDescription job) {
        Map<String, String> values = buildValues(resumeJson, job);
        List<String> missing = new ArrayList<>();
        for (String placeholder : extractPlaceholders(bodyText)) {
            String value = values.get(placeholder);
            if (value == null || value.isBlank()) {
                if (!missing.contains(placeholder)) missing.add(placeholder);
            }
        }
        // 与提取/missing 使用同一 Pattern，保证三者口径一致（P1-3 修复）
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(bodyText);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                // 无值占位符原样保留，由 missing 机制负责提示
                matcher.appendReplacement(filled, Matcher.quoteReplacement(matcher.group()));
            } else {
                // quoteReplacement 防止值中的 $ / \ 被当作正则替换组引用
                matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(filled);
        return new FillResult(filled.toString(), missing);
    }

    private Map<String, String> buildValues(Map<String, Object> resumeJson, JobDescription job) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Object> basics = asMap(resumeJson == null ? null : resumeJson.get("basics"));
        values.put("candidateName", asString(basics.get("name")));
        values.put("location", asString(basics.get("location")));
        values.put("email", asString(basics.get("email")));
        values.put("phone", asString(basics.get("phone")));
        values.put("jobTitle", job == null ? "" : asString(job.getTitle()));
        values.put("companyName", job == null ? "" : asString(job.getCompanyName()));
        values.put("topSkill", firstSkill(resumeJson == null ? null : resumeJson.get("skills")));
        return values;
    }

    private String firstSkill(Object value) {
        if (!(value instanceof List<?> skills) || skills.isEmpty()) return "";
        Object first = skills.get(0);
        if (first instanceof String text) return text;
        if (first instanceof Map<?, ?> item) {
            return asString(item.get("name"));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record FillResult(String filledBody, List<String> missingPlaceholders) {}
}
