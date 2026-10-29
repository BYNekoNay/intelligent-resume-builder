package com.intelligentresume.auth.ratelimit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一条限流规则：**路径集合 + 分桶组 + 阈值 + 为什么限**。
 *
 * <p>三条不变量（都有对应的门禁用例，别改掉）：
 * <ol>
 *   <li><b>分桶组里不许出现路径变量</b>。此前 JD 解析用「具体路径」分桶，
 *       于是 {@code /api/jobs/1/parse} 与 {@code /api/jobs/2/parse} 各有一份预算，
 *       实际阈值被放大成 {@code 15 × JD 数}/分钟——换个 id 就绕过。分桶必须按**规则**而不是按 URL。</li>
 *   <li><b>方法参与匹配</b>。同路径的 GET 是读，不该消耗写预算。</li>
 *   <li>{@code rationale} 不允许为空：写不出理由的限流，阈值基本是拍脑袋，也没法在文档里交代。</li>
 * </ol>
 */
public final class RateLimitRule {

    private final String id;
    private final String bucketGroup;
    private final int limitPerMinute;
    private final Set<String> methods;
    private final List<String> pathPatterns;
    private final List<Pattern> compiled;
    private final String rationale;

    private RateLimitRule(String id, String bucketGroup, int limitPerMinute, Set<String> methods,
                          List<String> pathPatterns, String rationale) {
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("限流规则 " + id + " 必须说明理由（文档与评审都依赖它）");
        }
        this.id = id;
        this.bucketGroup = bucketGroup;
        this.limitPerMinute = limitPerMinute;
        this.methods = methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.pathPatterns = List.copyOf(pathPatterns);
        this.compiled = pathPatterns.stream().map(RateLimitRule::compile).toList();
        this.rationale = rationale;
    }

    /**
     * @param methods       参与限流的方法（如 POST）
     * @param pathPatterns  MVC 路由形态，占位符用 {@code {id}}；{@code /**} 表示前缀匹配
     * @param bucketGroup   分桶组名，**不得含路径变量或具体 id**
     * @param rationale     为什么限（会进文档表格）
     */
    public static RateLimitRule of(String id, Set<String> methods, List<String> pathPatterns,
                                   String bucketGroup, int limitPerMinute, String rationale) {
        return new RateLimitRule(id, bucketGroup, limitPerMinute, methods, pathPatterns, rationale);
    }

    public String id() { return id; }

    public String bucketGroup() { return bucketGroup; }

    public int limitPerMinute() { return limitPerMinute; }

    public Set<String> methods() { return methods; }

    public List<String> pathPatterns() { return pathPatterns; }

    public String rationale() { return rationale; }

    public boolean matches(String method, String path) {
        if (path == null || method == null) {
            return false;
        }
        if (!methods.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        for (Pattern pattern : compiled) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * MVC 路由形态 → 正则：占位符匹配单段（{@code [^/]+}），{@code /**} 匹配任意后缀。
     *
     * <p>用 {@link Pattern#quote} 包住字面量，避免路径里的 {@code .} 被当成通配
     * （否则 {@code /api/v1.0/x} 能匹配 {@code /api/v1x0/x}）。
     */
    static Pattern compile(String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return Pattern.compile(Pattern.quote(prefix) + "/.*");
        }
        StringBuilder regex = new StringBuilder();
        Matcher placeholder = Pattern.compile("\\{[^}]+\\}").matcher(pattern);
        int cursor = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(pattern.substring(cursor, placeholder.start()))).append("[^/]+");
            cursor = placeholder.end();
        }
        regex.append(Pattern.quote(pattern.substring(cursor)));
        return Pattern.compile(regex.toString());
    }
}
