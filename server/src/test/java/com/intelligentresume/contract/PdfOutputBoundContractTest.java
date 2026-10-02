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
 * PDF 导出体积上限门禁（静态，跨运行时）（第五十二批）。
 *
 * <p>背景：`app.pdf.max-output-bytes`（默认 10MB）在 `application.yml`、`application-local-h2.yml`、
 * `application-test.yml`、`server/.env.example` 四处声明，`UploadPathContractTest` 与
 * `web/src/api/export.ts` 的注释也把它当作「导出下载的响应体上限」，但**服务端没有任何消费点**、
 * pdf-service 侧也没有输出大小检查——声明即虚构：超出上限的渲染会被原样落盘并允许下载。
 * `docs/03` §9.7 要求「每个任务设置渲染超时、最大输入和输出大小」。
 *
 * <p>数值与执行点分散在 Spring 配置与 Node 服务两处，故用门禁固化四件事：
 * <ol>
 *   <li>yml 必须声明该键的默认值（否则「上限」无从配置）；</li>
 *   <li>应用侧确有消费点，且 {@code @Value} 兜底与 yml 默认一致；</li>
 *   <li>pdf-service 侧声明自己的上限并**真正用于比较**（只解析不比较等于没有上限）；</li>
 *   <li>service 上限 ≥ API 上限：API 上限是用户可见契约，service 上限是第二道闸；
 *       若 service 更小，API 声明的允许区间不可达（合法导出会在服务端被拒）。</li>
 * </ol>
 */
class PdfOutputBoundContractTest {

    @Test
    @DisplayName("yml 声明输出上限默认值，且应用侧兜底与之一致")
    void apiDeclaresAndConsumesOutputBound() throws Exception {
        String apiYaml = read("server/src/main/resources/application.yml");
        long apiDefault = yamlDefault(apiYaml, "PDF_MAX_OUTPUT_BYTES");

        String client = read("server/src/main/java/com/intelligentresume/export/service/PdfServiceClient.java");
        assertTrue(client.contains("@Value(\"${app.pdf.max-output-bytes:" + apiDefault + "}\")"),
                "PdfServiceClient 必须消费 app.pdf.max-output-bytes，且 @Value 兜底与 application.yml 默认值一致（"
                        + apiDefault + " 字节）——该键此前在四处配置声明却无任何消费点（声明即虚构）");
        assertTrue(client.contains("pdfBytes.length > maxOutputBytes"),
                "PdfServiceClient 必须把 max-output-bytes 真正用于渲染结果比较，否则上限不执行");
    }

    @Test
    @DisplayName("pdf-service 声明输出上限并用于比较，且不低于 API 上限")
    void serviceDeclaresAndEnforcesOutputBoundAtLeastApi() throws Exception {
        String serviceJs = read("pdf-service/src/server.js");
        long serviceDefault = serviceDefault(serviceJs, "PDF_SERVICE_MAX_OUTPUT_BYTES");
        assertTrue(serviceJs.contains("pdf.length > maxOutputBytes"),
                "pdf-service 必须把 PDF_SERVICE_MAX_OUTPUT_BYTES 真正用于渲染结果比较（第二道闸）");

        long apiDefault = yamlDefault(read("server/src/main/resources/application.yml"), "PDF_MAX_OUTPUT_BYTES");
        assertTrue(serviceDefault >= apiDefault,
                "pdf-service 输出上限（" + serviceDefault + "）不得低于 API 上限（" + apiDefault
                        + "）：API 上限是用户可见契约，service 更小会让合法导出在服务端被拒");
    }

    @Test
    @DisplayName(".env.example 暴露输出上限旋钮（可运维）")
    void envExampleExposesOutputBound() throws Exception {
        String envExample = read("server/.env.example");
        assertTrue(envExample.contains("PDF_MAX_OUTPUT_BYTES="),
                "server/.env.example 必须暴露 PDF_MAX_OUTPUT_BYTES，否则部署侧无法调整导出体积上限");
    }

    /** 解析 application.yml 中 `${ENV_NAME:默认值}` 形式的默认值。 */
    private long yamlDefault(String yaml, String envName) {
        Matcher matcher = Pattern.compile("\\$\\{" + envName + ":(\\d+)\\}").matcher(yaml);
        assertTrue(matcher.find(), "application.yml 未声明 " + envName + " 的默认值");
        return Long.parseLong(matcher.group(1));
    }

    /** 解析 pdf-service/src/server.js 中 `positiveInteger('ENV_NAME', 15_000, 1)` 的默认值（下划线数字字面量）。 */
    private long serviceDefault(String serviceJs, String envName) {
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