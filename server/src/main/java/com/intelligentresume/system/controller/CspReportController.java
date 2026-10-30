package com.intelligentresume.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.observability.AppObservability;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * CSP 违规报告接收端点（B6-1）。
 *
 * <p><b>它为什么存在</b>：nginx 下发的 {@code Content-Security-Policy-Report-Only}
 * 只是「浏览器会报告违规」，报告本身必须有地方去 —— 此前没有 report-uri，
 * 观察期的验收条件「线上 CSP violation 报告为零」根本无法判定
 * （见 {@code deploy/nginx/security-headers.conf} 的切换条件注释）。
 *
 * <p><b>安全设计</b>：
 * <ul>
 *   <li><b>匿名可达</b>（SecurityConfig permitAll）：报告由浏览器自动发出，不带任何凭证；</li>
 *   <li><b>恒返回 204</b>：即使解析失败也不给浏览器报错细节 —— 该端点的消费方是浏览器，
 *       不是人，错误细节只会帮攻击者探测；</li>
 *   <li><b>读入即限长</b>（{@value #MAX_BODY_BYTES} 字节）：在反序列化之前截断，
 *       垃圾超大 payload 不会进入 Jackson；</li>
 *   <li><b>指标标签取封闭集</b>：directive 先过白名单，未知值一律归 {@code other}。
 *       报告来自浏览器且可被任意伪造，直接把原始 directive 当 Prometheus 标签
 *       会造成标签基数爆炸（这正是 {@link AppObservability} 类注释里「拒绝业务 payload」的同类约束）；</li>
 *   <li><b>日志不落原文</b>：只记 directive 与截断清洗后的 URI 字段，换行/制表符抹成空格防日志注入。</li>
 * </ul>
 *
 * <p><b>频率</b>：POST 属写操作，天然落在限流兜底规则 {@code write-default}（见
 * {@code RateLimitRegistry}，由 {@code RateLimitRegistryContractTest} 与 docs/05 对拍），
 * 与所有其它未显式限流的写端点同强度，不单独开规则。
 */
@RestController
public class CspReportController {

    /** 接收路径。nginx 的 CSP 头以相对路径指向它，所有入口（直连/容器/TLS 测试）同源可达。 */
    public static final String PATH = "/api/system/csp-reports";

    /** 请求体读取上限（字节）。合法 CSP 报告通常远小于此；超过即视为滥用输入。 */
    static final int MAX_BODY_BYTES = 8192;

    /** 日志里字段值与原文摘录的上限。 */
    private static final int MAX_LOG_FIELD = 200;

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);

    /**
     * 可作 Prometheus 标签的 directive 封闭集。
     *
     * <p>取自本站 CSP 策略可能涉及的指令 + W3C CSP3 常见指令；白名单外的值（包括
     * 攻击者伪造的任意字符串）一律映射 {@code TAG_OTHER}。
     */
    private static final Set<String> KNOWN_DIRECTIVES = Set.of(
            "default-src", "script-src", "style-src", "img-src", "connect-src",
            "font-src", "media-src", "object-src", "frame-src", "worker-src",
            "child-src", "base-uri", "form-action", "frame-ancestors");

    /** 白名单外 directive 的统一标签值。 */
    public static final String TAG_OTHER = "other";
    /** body 缺失/非法/超限截断后不可解析时的统一标签值。 */
    public static final String TAG_UNPARSEABLE = "unparseable";

    private final ObjectMapper objectMapper;
    private final AppObservability observability;

    public CspReportController(ObjectMapper objectMapper, AppObservability observability) {
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

    /**
     * 接收一份违规报告。恒 {@code 204}，从不抛出给调用方。
     *
     * <p>消费两种 content-type：{@code application/csp-report}（report-uri 规范值）
     * 与 {@code application/json}（部分客户端实现），语义相同。
     */
    @PostMapping(value = "/api/system/csp-reports",
            consumes = {"application/csp-report", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> report(HttpServletRequest request) {
        String body = readTruncated(request);
        JsonNode report = parse(body);
        if (report == null) {
            observability.recordCspViolation(TAG_UNPARSEABLE);
            return ResponseEntity.noContent().build();
        }

        JsonNode cspReport = report.path("csp-report");
        String directive = firstText(cspReport, "effective-directive", "violated-directive");
        String tag = directiveTag(directive);
        observability.recordCspViolation(tag);

        String blocked = firstText(cspReport, "blocked-uri");
        String document = firstText(cspReport, "document-uri");
        // 日志只留定位所需的三个字段：directive 已进指标；URI 帮助判断是哪个页面在报违规。
        log.warn("CSP violation report: directive={}, blockedUri={}, documentUri={}, excerpt={}",
                directive, sanitize(blocked), sanitize(document), sanitize(excerpt(body)));
        return ResponseEntity.noContent().build();
    }

    /** 手动读 body 并在 {@value #MAX_BODY_BYTES} 字节处截断 —— 限长发生在反序列化之前。 */
    private String readTruncated(HttpServletRequest request) {
        byte[] buffer = new byte[MAX_BODY_BYTES];
        int read = 0;
        try (var in = request.getInputStream()) {
            while (read < MAX_BODY_BYTES) {
                int n = in.read(buffer, read, MAX_BODY_BYTES - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (IOException reading) {
            return "";
        }
        return new String(buffer, 0, read, StandardCharsets.UTF_8);
    }

    /** 解析失败返回 null（调用方计 unparseable），从不抛出。 */
    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.isObject() ? node : null;
        } catch (IOException notJson) {
            return null;
        }
    }

    /** 白名单映射：未知 directive 一律 {@link #TAG_OTHER}，杜绝标签基数被伪造值撑爆。 */
    private String directiveTag(String directive) {
        if (directive == null) {
            return TAG_OTHER;
        }
        String normalized = directive.trim().toLowerCase();
        return KNOWN_DIRECTIVES.contains(normalized) ? normalized : TAG_OTHER;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /** 摘录前 {@value #MAX_LOG_FIELD} 字符。 */
    private String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_LOG_FIELD ? body : body.substring(0, MAX_LOG_FIELD) + "…";
    }

    /** 抹掉换行/制表防日志注入；超长截断。 */
    private String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ");
        return cleaned.length() <= MAX_LOG_FIELD ? cleaned : cleaned.substring(0, MAX_LOG_FIELD) + "…";
    }
}
