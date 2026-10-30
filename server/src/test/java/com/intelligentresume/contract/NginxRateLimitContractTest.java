package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * nginx 边缘限流契约（静态，B6-2）。
 *
 * <p><b>背景</b>：应用层 {@code RateLimitFilter} 按「规则组 + IP」做业务级预算，
 * 但它的规则表之外（读端点、未来新增的路径）没有频率闸；边缘层此前完全裸奔。
 * B6-2 在宿主直连版 nginx（生产真实入口）补一层按 IP 的洪水面闸。
 *
 * <p>三条失败模式，都是「配置在、保护不在」的静默形态：
 * <ol>
 *   <li><b>zone 声明被放错上下文</b>：{@code limit_req_zone} 只允许出现在 http 上下文
 *       （sites-enabled 文件顶层）。写进 server 块 → {@code nginx -t} 直接失败（尚能发现）；
 *       但更隐蔽的是 zone 声明了而 location 没引用 —— 限流从未生效；</li>
 *   <li><b>zone 名对不上</b>：location 引用了不存在的 zone → nginx -t 失败、reload 被拒，
 *       部署探针会红，但那时服务还在跑旧配置 —— 契约应该在部署前就拦住；</li>
 *   <li><b>限流加错了层</b>：容器拓扑 edge → web → api 中，web 层的直连对端是 **edge 容器**
 *       —— 在 web 层按 {@code $binary_remote_addr} 限流等于全站共享一个桶（30r/s 全站限额），
 *       正常用户互相挤兑。这与 {@code DeployProxyClientIpContractTest} 记录的
 *       「内层覆写 XFF 会把限流分桶退化为 edge 地址」是同一类「对端不是真实客户端」陷阱。</li>
 * </ol>
 *
 * <p><b>容器版为何本轮不做</b>：edge.conf 是 envsubst 模板（{@code ${PUBLIC_HOST}}），
 * 加 {@code $binary_remote_addr} 需要 {@code $$} 转义，且容器拓扑当前不在生产运行路径 ——
 * 加一段从未在真实环境验证过的配置违背本项目「落地即验证」的纪律。宿主版先行，容器版
 * 启用时同步（见 {@link #innerProxyLayersMustNotLimitByRemoteAddr()} 的反向钉子）。
 */
class NginxRateLimitContractTest {

    private static final String HOST_CONF = "deploy/nginx/host.conf";

    private static final String ZONE_NAME = "api_per_ip";

    @Test
    @DisplayName("zone 必须声明在 http 上下文（server 块之前），且按 $binary_remote_addr 分桶")
    void zoneDeclaredInHttpContextBeforeServerBlock() throws Exception {
        String conf = SourceText.read(repoFile(HOST_CONF));

        int zoneAt = conf.indexOf("limit_req_zone $binary_remote_addr zone=" + ZONE_NAME + ":");
        assertTrue(zoneAt >= 0,
                HOST_CONF + " 缺少 limit_req_zone 声明（zone=" + ZONE_NAME + "）—— 边缘洪水面闸失效，"
                        + "应用层限流规则表之外的读端点与未来新增路径完全裸奔");

        int serverAt = conf.indexOf("server {");
        assertTrue(serverAt >= 0, "样本自身失效：" + HOST_CONF + " 应包含 server 块");
        assertTrue(zoneAt < serverAt,
                "limit_req_zone 声明出现在 server 块之后 —— 它只允许在 http 上下文"
                        + "（sites-enabled 文件被 nginx include 进 http{}），放错位置 nginx -t 直接失败");
    }

    @Test
    @DisplayName("/api/ location 必须引用该 zone，且 429 与应用层限流同状态码")
    void apiLocationAppliesZoneWith429() throws Exception {
        String conf = SourceText.read(repoFile(HOST_CONF));

        String apiBody = locationBody(conf, "/api/");
        assertTrue(apiBody.contains("limit_req zone=" + ZONE_NAME),
                "/api/ location 未引用 limit_req zone=" + ZONE_NAME
                        + " —— zone 声明了而没被引用，限流从未生效（nginx -t 照样通过）");
        assertTrue(apiBody.contains("limit_req_status 429"),
                "/api/ location 缺少 limit_req_status 429 —— nginx 默认返回 503，"
                        + "而应用层限流是 429：同一类「请求过快」在两层之间状态码不一致，"
                        + "前端与黑盒断言按 429 语义处理");
    }

    @Test
    @DisplayName("内层代理层不得按 $binary_remote_addr 限流（对端是 edge 容器 = 全站共享桶）")
    void innerProxyLayersMustNotLimitByRemoteAddr() throws Exception {
        for (String config : new String[]{
                "deploy/nginx/web.conf", "web/nginx.conf"}) {
            String conf = SourceText.read(repoFile(config));
            assertFalse(conf.contains("limit_req"),
                    config + " 出现 limit_req —— 该层的直连对端是 edge 容器而非真实客户端，"
                            + "按 $binary_remote_addr 分桶会把全站流量挤进同一个桶（30r/s 全站限额）。"
                            + "容器拓扑的边缘限流必须在 edge.conf（直连真实客户端的一层）做，"
                            + "且注意它是 envsubst 模板，$ 需转义");
        }
    }

    /** 极简 location 块提取：取 {@code location <prefix> { ... }} 的正文（注释已抹白）。 */
    private String locationBody(String conf, String prefix) {
        String needle = "location " + prefix + " {";
        int at = conf.indexOf(needle);
        assertTrue(at >= 0, HOST_CONF + " 找不到 " + needle + "（门禁解析失效，不得静默通过）");
        int open = conf.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < conf.length(); i++) {
            char c = conf.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return conf.substring(open + 1, i);
            }
        }
        throw new AssertionError(HOST_CONF + " 的 " + needle + " 块花括号不配平");
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
