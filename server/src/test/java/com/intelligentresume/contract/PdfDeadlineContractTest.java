package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 导出死线链门禁（静态，跨运行时）：pdf-service 内层预算 &lt; API 读超时 &lt; 领取租约，
 * 且租约覆盖单个领取批次。
 *
 * <p>背景（第二十一批）：导出任务经「pdf-service 排队/渲染 → API 读超时 → worker 租约」三层
 * 时间边界。任一环漂移都会造成静默故障——
 * <ul>
 *   <li>读超时早于服务端上界：合法慢渲染（服务端自身预算内）被客户端先断开，
 *       渲染被浪费、任务被误判失败；</li>
 *   <li>租约不能覆盖「batch-size × 读超时 + 收尾」：批量在循环内串行处理而在领取时一次性
 *       写入租约，后续任务会在处理途中被第二 worker 接管 → 重复渲染。</li>
 * </ul>
 * 数值分散在 Spring 配置与 Node 服务两处，故用门禁固化关系而不是靠注释。
 */
class PdfDeadlineContractTest {

    @Test
    @DisplayName("pdf-service 上界 < API 读超时 < 领取租约，且租约覆盖 batch × 读超时")
    void deadlineChainIsOrdered() throws Exception {
        String apiYaml = read("server/src/main/resources/application.yml");
        String serviceJs = read("pdf-service/src/server.js");

        long queueMs = serviceDefaultMs(serviceJs, "PDF_SERVICE_QUEUE_TIMEOUT_MS");
        long renderMs = serviceDefaultMs(serviceJs, "PDF_SERVICE_RENDER_TIMEOUT_MS");
        long readTimeoutSeconds = yamlDefault(apiYaml, "PDF_RENDER_TIMEOUT_S");
        long batchSize = yamlDefault(apiYaml, "PDF_WORKER_BATCH");
        long leaseSeconds = yamlDefault(apiYaml, "PDF_WORKER_LEASE_S");

        // 服务端上界 = 排队上限 + setContent + pdf 两次页面操作
        long serviceWorstCaseSeconds = (queueMs + 2 * renderMs) / 1000;
        assertTrue(readTimeoutSeconds >= serviceWorstCaseSeconds + 3,
                "API 读超时（" + readTimeoutSeconds + "s）必须晚于 pdf-service 上界（" + serviceWorstCaseSeconds
                        + "s = 排队 " + queueMs + "ms + 2×渲染 " + renderMs + "ms）并留余量，"
                        + "否则合法慢渲染会被客户端先断开（浪费渲染 + 任务误判失败）");

        long batchBudgetSeconds = batchSize * readTimeoutSeconds + 10;
        assertTrue(leaseSeconds >= batchBudgetSeconds,
                "领取租约（" + leaseSeconds + "s）必须覆盖单个批次（batch-size " + batchSize + " × 读超时 "
                        + readTimeoutSeconds + "s + 收尾余量 = " + batchBudgetSeconds + "s），"
                        + "否则批次内后续任务会在处理途中被第二 worker 接管（重复渲染）");
    }

    @Test
    @DisplayName("应用层 @Value 兜底与 application.yml 默认值一致（防三处默认漂移）")
    void applicationFallbacksMatchYamlDefaults() throws Exception {
        String apiYaml = read("server/src/main/resources/application.yml");
        long readTimeoutSeconds = yamlDefault(apiYaml, "PDF_RENDER_TIMEOUT_S");
        long batchSize = yamlDefault(apiYaml, "PDF_WORKER_BATCH");

        String client = read("server/src/main/java/com/intelligentresume/export/service/PdfServiceClient.java");
        assertTrue(client.contains("@Value(\"${app.pdf.render-timeout-seconds:" + readTimeoutSeconds + "}\")"),
                "PdfServiceClient 的 @Value 兜底必须与 application.yml 默认读超时一致（" + readTimeoutSeconds + "s）");

        String worker = read("server/src/main/java/com/intelligentresume/export/service/ExportTaskWorker.java");
        assertTrue(worker.contains("@Value(\"${app.pdf.worker.batch-size:" + batchSize + "}\")"),
                "ExportTaskWorker 的 @Value 兜底必须与 application.yml 默认批次一致（" + batchSize + "）");
    }

    /** 解析 application.yml 中 `${ENV_NAME:默认值}` 形式的默认值。 */
    private long yamlDefault(String yaml, String envName) {
        Matcher matcher = Pattern.compile("\\$\\{" + envName + ":(\\d+)\\}").matcher(yaml);
        assertTrue(matcher.find(), "application.yml 未声明 " + envName + " 的默认值");
        return Long.parseLong(matcher.group(1));
    }

    /** 解析 pdf-service/src/server.js 中 `positiveInteger('ENV_NAME', 15_000, 1)` 的默认值（下划线数字字面量）。 */
    private long serviceDefaultMs(String serviceJs, String envName) {
        Matcher matcher = Pattern.compile(
                "positiveInteger\\('" + envName + "',\\s*([\\d_]+)").matcher(serviceJs);
        assertTrue(matcher.find(), "pdf-service/src/server.js 未声明 " + envName + " 的默认值");
        return Long.parseLong(matcher.group(1).replace("_", ""));
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private String read(String relative) throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return SourceText.read(target);
    }
}