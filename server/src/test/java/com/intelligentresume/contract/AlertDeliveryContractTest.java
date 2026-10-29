package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警出口的静态契约（2026-10-29）。
 *
 * <p>背景：15 条告警规则能真评估、真 firing，但出口只落到 journald —— 没有投递通道就没人知道。
 * 补出口的同时必须钉住四条边界，否则"出口"本身会变成新的风险：
 * <ol>
 *   <li><b>未配置就不外发</b>：测试环境的纪律，不能让一个默认值把告警发到外网；</li>
 *   <li><b>payload 不含 PII</b>：告警名与计数之外不带任何业务值（用户、邮箱、简历内容、URL）；</li>
 *   <li><b>外呼有超时上限</b>：不能让一个卡住的 webhook 把 timer 挂死；</li>
 *   <li><b>状态文件写失败不改变退出码</b>：观测手段不得影响业务结论。</li>
 * </ol>
 *
 * <p>另钉 watchdog 的判据方向：必须看「最近一次**成功**」而不是「最近一次运行」——
 * prometheus 持续不可达时每次都写状态，用最近一次运行会把"一直在失败"误判成健康。
 */
class AlertDeliveryContractTest {

    private static final String SCRIPT = "deploy/check-alerts.sh";

    @Test
    @DisplayName("未配置 ALERT_WEBHOOK_URL 时绝不外发")
    void noWebhookMeansNoOutbound() throws Exception {
        String source = stripComments(SourceText.read(repoFile(SCRIPT)));
        assertTrue(source.contains("WEBHOOK=\"${ALERT_WEBHOOK_URL:-}\""),
                SCRIPT + " 的 webhook 变量必须默认为空 —— 默认值会让测试环境把告警发到外网");

        // 守卫文本本身含 "$WEBHOOK"，因此按「出现次数 + 位置」判定：
        // 期望恰好两处 —— 守卫一处、外呼一处，且外呼在守卫之后。
        String token = "\"$WEBHOOK\"";
        java.util.List<Integer> occurrences = new java.util.ArrayList<>();
        for (int i = source.indexOf(token); i >= 0; i = source.indexOf(token, i + 1)) {
            occurrences.add(i);
        }
        assertEquals(2, occurrences.size(),
                SCRIPT + " 里 \"$WEBHOOK\" 出现 " + occurrences.size() + " 处（期望 2：守卫 + 一次外呼）。"
                        + "多出来的那处很可能在守卫之外，等于未配置时也会外发");
        assertTrue(occurrences.get(1) > occurrences.get(0),
                SCRIPT + " 的外呼必须出现在守卫之后");
    }

    @Test
    @DisplayName("外呼 payload 只含告警名与计数，且超时 ≤10s")
    void payloadHasNoPiiAndTimesOut() throws Exception {
        String source = stripComments(SourceText.read(repoFile(SCRIPT)));

        // 取外呼块：第一处 "$WEBHOOK" 是守卫条件本身，第二处才是外呼 URL
        int guardUse = source.indexOf("\"$WEBHOOK\"");
        assertTrue(guardUse > 0, SCRIPT + " 找不到 \"$WEBHOOK\" —— 锚点失效（不得当成通过）");
        int use = source.indexOf("\"$WEBHOOK\"", guardUse + 1);
        assertTrue(use > guardUse, SCRIPT + " 找不到第二处 \"$WEBHOOK\"（外呼 URL）—— 锚点失效");
        int curlStart = source.lastIndexOf("curl", use);
        assertTrue(curlStart > 0, SCRIPT + " 外呼 URL 之前找不到 curl —— 锚点失效");
        int blockEnd = source.indexOf("|| log", use);
        assertTrue(blockEnd > use, SCRIPT + " 找不到外呼失败分支（|| log）—— 锚点失效");
        String block = source.substring(curlStart, blockEnd);

        // ① payload 只允许出现计数与告警名
        for (String forbidden : new String[]{"alerts_json", "labels", "traceId", "email", "username", "summary", "PROM_URL"}) {
            assertFalse(block.contains(forbidden),
                    SCRIPT + " 的外呼块出现了 " + forbidden + " —— 告警出口不得携带业务数据/用户标识/内部 ID");
        }
        assertTrue(block.contains("${NC}") || block.contains("${CRIT}"),
                SCRIPT + " 的外呼 payload 应只含告警计数与告警名");

        // ② 超时上限：外呼块内必须有 -m N 且 N ≤ 10
        Matcher timeout = Pattern.compile("-m (\\d+)").matcher(block);
        assertTrue(timeout.find(), SCRIPT + " 外呼块内没有 -m 超时 —— 卡住的 webhook 会把 timer 挂死");
        int seconds = Integer.parseInt(timeout.group(1));
        assertTrue(seconds > 0 && seconds <= 10,
                SCRIPT + " 的外呼超时是 " + seconds + "s，超过 10s 上限 —— 卡住的 webhook 会把 timer 挂死");
    }

    @Test
    @DisplayName("状态文件写失败不得改变退出码（观测手段不影响业务结论）")
    void stateWriteFailureDoesNotChangeExitCode() throws Exception {
        String source = stripComments(SourceText.read(repoFile(SCRIPT)));

        Matcher fn = Pattern.compile("write_state\\(\\) \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        assertTrue(fn.find(), SCRIPT + " 缺少 write_state 函数 —— 锚点失效");
        String body = fn.group(1);
        assertFalse(body.contains("exit "),
                SCRIPT + " 的 write_state 里有 exit —— 写状态失败会改变业务退出码");
        assertTrue(body.contains("|| log") || body.contains("|| true"),
                SCRIPT + " 的 write_state 未吞掉写入失败 —— 目录不可写时会把成功误报成失败");

        // 每个 write_state 调用都不得被 `|| exit` 之类接管
        assertFalse(Pattern.compile("write_state [^\\n]*\\|\\|").matcher(source).find(),
                SCRIPT + " 的 write_state 调用被 || 接管 —— 状态写入失败会污染退出码");
    }

    @Test
    @DisplayName("watchdog 以「最近一次成功」为基准，阈值 = 3 个轮询周期")
    void watchdogUsesLastSuccessNotLastRun() throws Exception {
        String source = stripComments(SourceText.read(repoFile(SCRIPT)));

        assertTrue(source.contains("\"${1:-}\" = \"--watchdog\""),
                SCRIPT + " 缺少 --watchdog 模式（监控自死检测的入口）");
        assertTrue(Pattern.compile("'\\$2 == 0").matcher(source).find()
                        || source.contains("$2 == 0"),
                SCRIPT + " 的 watchdog 未按「最近一次成功（退出码 0）」筛选取基准时间 —— "
                        + "用最近一次运行会把「一直在失败」误判成健康");
        assertTrue(Pattern.compile("WATCHDOG_MAX_AGE_SEC=\"\\$\\{WATCHDOG_MAX_AGE_SEC:-900\\}\"").matcher(source).find(),
                SCRIPT + " 的 watchdog 阈值不是 900s（3 个 5 分钟轮询周期）—— 过短会误报，过长会漏掉整个故障窗口");
        assertTrue(source.contains("exit 2"),
                SCRIPT + " 的 watchdog 超阈值时必须以退出码 2 结束（systemd 才会记 failed）");
    }

    private String stripComments(String text) {
        return text.replaceAll("(?m)^\\s*#.*$", "").replace("\r", "");
    }

    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
