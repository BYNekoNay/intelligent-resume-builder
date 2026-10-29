package com.intelligentresume.auth.ratelimit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 限流注册表：**唯一**一处表达「哪些请求受限、按什么分桶、阈值多少、为什么」。
 *
 * <p>改造前这套知识散在 {@code RateLimitFilter.limitFor()} 的 if 链里，结果是：
 * <ul>
 *   <li>新增受限端点必须改 Filter 源码，没人提醒就会漏（{@code /api/exports/pdf}、
 *       {@code /api/ats/check}、整个 {@code /api/ai/**} 长期完全没有频率闸）；</li>
 *   <li>{@code /api/jobs/{id}/parse} 拿**具体路径**当分桶键，阈值被放大成
 *       {@code 15 × JD 数}/分钟，换个 JD id 即绕过；</li>
 *   <li>猜不出「哪些端点其实不受限」——没有清单就没有缺口。</li>
 * </ul>
 *
 * <p>现在的语义：**规则有序，先命中先用**，最后一条是写操作兜底（{@link #WRITE_DEFAULT_ID}）。
 * 于是「无上限的写端点」在结构上不存在；要放宽某个新端点，必须显式加一条带理由的规则。
 * 规则清单与 {@code docs/05-接口设计说明书.md} 的限流表由
 * {@code RateLimitRegistryContractTest} 双向对拍。
 */
@Component
public class RateLimitRegistry {

    /** 兜底规则 id：所有未被前面规则命中的写操作都落在这里。 */
    public static final String WRITE_DEFAULT_ID = "write-default";

    /** 写操作方法集（读不受兜底限制：GET 的成本由缓存与索引承担，不设频率闸）。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Set<String> POST_ONLY = Set.of("POST");

    private final List<RateLimitRule> rules;

    public RateLimitRegistry(RateLimitProperties properties) {
        this.rules = build(properties);
    }

    /** 命中第一条匹配规则；未命中返回 {@code null}（仅可能发生在非写请求上）。 */
    public RateLimitRule resolve(String method, String path) {
        if (path == null) {
            return null;
        }
        for (RateLimitRule rule : rules) {
            if (rule.matches(method, path)) {
                return rule;
            }
        }
        return null;
    }

    /** 全部规则（有序）；门禁据此与文档对拍。 */
    public List<RateLimitRule> rules() {
        return rules;
    }

    /**
     * 构造规则清单。{@code public} 是刻意的：`RateLimitRegistryContractTest` 用它把规则与
     * docs/05 的表格、application.yml 的默认值逐格对拍，不需要为了测试起整个 Spring 容器。
     */
    public static List<RateLimitRule> build(RateLimitProperties properties) {
        return List.of(
                // —— 口令验证面：最严一档 ——
                RateLimitRule.of("login", POST_ONLY,
                        List.of("/api/auth/login", "/api/auth/deletion/restore"),
                        "login", properties.getLoginPerMinute(),
                        "口令验证面：删除撤销与登录同强度（用户名/邮箱 + 密码），共享桶防止交替获得双倍预算"),
                RateLimitRule.of("register", POST_ONLY,
                        List.of("/api/auth/register"),
                        "register", properties.getRegisterPerMinute(),
                        "批量注册会污染账号表并放大下游 AI 成本"),
                RateLimitRule.of("refresh", POST_ONLY,
                        List.of("/api/auth/refresh"),
                        "refresh", properties.getRefreshPerMinute(),
                        "刷新令牌可被循环调用换取 access token"),
                RateLimitRule.of("credential-change", POST_ONLY,
                        List.of("/api/auth/me/password", "/api/auth/me/email"),
                        "credential-change", properties.getChangeCredentialPerMinute(),
                        "改密/改邮箱都校验当前密码，不限流可用被盗 token 无限试密码"),
                RateLimitRule.of("account-export", POST_ONLY,
                        List.of("/api/auth/export"),
                        "account-export", properties.getAccountExportPerMinute(),
                        "数据放大器：单次重读并序列化整账号，成本随数据量线性增长"),

                // —— 资源放大器：单次请求吃 CPU 或 Chromium ——
                RateLimitRule.of("resume-import-parse", POST_ONLY,
                        List.of("/api/resume-imports/parse"),
                        "resume-import-parse", properties.getResumeImportParsePerMinute(),
                        "PDFBox/POI 全内存解析最大 5MB 文件，最重的 CPU 端点"),
                RateLimitRule.of("jd-parse", POST_ONLY,
                        List.of("/api/jobs/{id}/parse"),
                        "jd-parse", properties.getJdParsePerMinute(),
                        "JD 文本解析；**按规则组分桶**，此前按具体 JD id 分桶等于把阈值乘以 JD 数"),
                RateLimitRule.of("pdf-render", POST_ONLY,
                        List.of("/api/exports/pdf", "/api/exports/tasks/{id}/retry"),
                        "pdf-render", properties.getPdfRenderPerMinute(),
                        "每次渲染占用一个 Chromium 上下文，pdf-service 只有 4 并发 / 16 队列"),

                RateLimitRule.of("ats-check", POST_ONLY,
                        List.of("/api/ats/check"),
                        "ats-check", properties.getAtsCheckPerMinute(),
                        "规则打分 + PDF/DOCX 文本重排 + 可能启动 AI 任务，CPU 与配额双消耗"),

                // —— AI 与面试：真正的上限是日配额，这里是突发闸 ——
                RateLimitRule.of("ai-generate", POST_ONLY,
                        List.of("/api/ai/**", "/api/communications/ai-generate",
                                "/api/communications/ai-generate/preview"),
                        "ai-generate", properties.getAiGeneratePerMinute(),
                        "AI 创建/预览类端点共享突发闸：防止一人的日配额在一分钟内打满，也会打爆模型限流"),
                RateLimitRule.of("interview-turn", POST_ONLY,
                        List.of("/api/interviews/start", "/api/interviews/{id}/answer",
                                "/api/interviews/{id}/ai/retry"),
                        "interview-turn", properties.getInterviewTurnPerMinute(),
                        "每个回合都触发一次模型评估（实测均 108s），并发送面试事件通知"),

                // —— 桌面端重操作 ——
                RateLimitRule.of("desktop-mutation", POST_ONLY,
                        List.of("/api/desktop/import/apply", "/api/desktop/backups/{id}/restore"),
                        "desktop-mutation", properties.getDesktopMutationPerMinute(),
                        "导入落库与备份恢复都是破坏性写操作，误点或脚本循环的成本高"),

                // —— 会触发出站探测的读端点（唯一受限的 GET） ——
                RateLimitRule.of("health-detail", Set.of("GET"),
                        List.of("/api/system/health/detail"),
                        "health-detail", properties.getHealthDetailPerMinute(),
                        "每次探测 PDF 服务与模型 PROVIDER（出站请求），可被用来放大外部调用"),

                // —— 兜底：保证「没有无上限的写端点」——
                RateLimitRule.of(WRITE_DEFAULT_ID, WRITE_METHODS,
                        List.of("/api/**"),
                        WRITE_DEFAULT_ID, properties.getWriteDefaultPerMinute(),
                        "所有未被上面命中的写操作：阈值宽松，只为拦住脚本级误用与放大攻击")
        );
    }
}
