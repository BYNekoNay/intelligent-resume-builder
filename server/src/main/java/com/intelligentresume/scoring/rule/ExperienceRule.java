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
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：JD 常写「3 年以上经验」，本规则检查简历的工作经历条目
 *       折算出的年限够不够，按比例给分——年限够给满分，不够按比例打折。</li>
 *   <li><b>在链路哪里</b>：与 KeywordRule 同级，由 {@code ScoringService} 经
 *       {@link RuleRegistry} 调用；输入是 JD requirements 文本 + 简历 JSON 的 work 数组；
 *       分数乘 0.2 权重进总分。</li>
 *   <li><b>改坏它用户会看到什么</b>：年限估算口径改动 → 所有人的经验分整体跳变；
 *       正则放宽 → "3-5年" 取 3 取 5 的行为变化会直接影响分数。</li>
 * </ul>
 *
 * <p>规则：
 * <ul>
 *   <li>从 JD requirements 中提取 "X年以上经验" 的年限要求</li>
 *   <li>从简历 work 条目估算总年限（有日期则计算，否则每条估 2 年）</li>
 *   <li>JD 无经验要求 → 满分 100</li>
 *   <li>score = min(resumeYears / requiredYears, 1.0) * 100</li>
 * </ul>
 *
 * <p>实现口径（与上面第 2 条的措辞不完全一致，改前请先看代码）：
 * <ul>
 *   <li>当前<b>不解析任何起止日期</b>，简历年限 = {@code work} 条目数 × 2；兼职/实习/时间重叠都会被按 2 年计。</li>
 *   <li>年限要求取 requirements 中<b>命中的最大值</b>（正则 {@code (\d+)\s*年}）：
 *       "3年以上"取 3，"3-5年"只会命中 "5年" 而取 5 —— 区间用右端、多条目取最大，属已知宽口径。</li>
 *   <li>无 requirements 或无年限要求 → 直接 100 分；简历无 work 数组 → 0 年，即该维度 0 分。</li>
 *   <li>规则名 {@code "experience"} 是权重表的键，不能改。</li>
 * </ul>
 */
@Component
public class ExperienceRule {

    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d+)\\s*年");
    /**
     * 每条工作经历默认折算的年数。
     *
     * <p>当前实现不解析简历里的起止日期（见类注释实现口径），无法精确计算时
     * 用「每段经历按 2 年估」的粗略折算。该值为实现内约定的估算基数，
     * 未找到外部明确依据，改前先全仓搜 {@code DEFAULT_YEARS_PER_JOB} 并评估
     * 对已存评分结果一致性的影响。
     */
    private static final int DEFAULT_YEARS_PER_JOB = 2;

    public String name() {
        return "experience";
    }

    /**
     * 评估经验覆盖度。
     *
     * <p>步骤：① 从 JD requirements 里用正则抽最大年限要求（如 "3年以上" → 3）；
     * ② 没有年限要求直接给满分——JD 不卡经验就不该惩罚；
     * ③ 简历年限 = work 条目数 × 2（粗估）；④ 分数 = min(简历年限 ÷ 要求年限, 1) × 100，
     * 超出要求不加分、只封顶满分。①在③之前——先确定标尺再量简历。
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
