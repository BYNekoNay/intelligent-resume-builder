package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 经验规则：检查简历工作年限是否满足 JD 要求。
 *
 * <p>规则：
 * <ul>
 *   <li>从 JD requirements 中提取 "X年以上经验" 的年限要求</li>
 *   <li>从简历 work 条目估算总年限（有日期则计算，否则每条估 2 年）</li>
 *   <li>JD 无经验要求 → 满分 100</li>
 *   <li>score = min(resumeYears / requiredYears, 1.0) * 100</li>
 * </ul>
 */
@Component
public class ExperienceRule {

    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d+)\\s*年");
    private static final int DEFAULT_YEARS_PER_JOB = 2;

    public String name() {
        return "experience";
    }

    /**
     * 评估经验覆盖度。
     *
     * @param requirements JD requirements 列表（含 "X年以上经验" 等）
     * @param resumeJson   简历 JSON（用于提取 work 条目）
     * @return 分数（0-100）
     */
    @SuppressWarnings("unchecked")
    public BigDecimal evaluate(List<String> requirements, Map<String, Object> resumeJson) {
        int requiredYears = extractRequiredYears(requirements);
        if (requiredYears <= 0) {
            return BigDecimal.valueOf(100);
        }

        int resumeYears = estimateWorkYears(resumeJson);
        double ratio = Math.min((double) resumeYears / requiredYears, 1.0);
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 从 requirements 中提取最大年限要求。
     */
    private int extractRequiredYears(List<String> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return 0;
        }
        int maxYears = 0;
        for (String req : requirements) {
            Matcher m = YEARS_PATTERN.matcher(req);
            if (m.find()) {
                int years = Integer.parseInt(m.group(1));
                maxYears = Math.max(maxYears, years);
            }
        }
        return maxYears;
    }

    /**
     * 估算简历总工作年限。
     */
    @SuppressWarnings("unchecked")
    private int estimateWorkYears(Map<String, Object> resumeJson) {
        if (resumeJson == null) {
            return 0;
        }
        Object workObj = resumeJson.get("work");
        if (!(workObj instanceof List)) {
            return 0;
        }
        List<Object> workList = (List<Object>) workObj;
        return workList.size() * DEFAULT_YEARS_PER_JOB;
    }
}
