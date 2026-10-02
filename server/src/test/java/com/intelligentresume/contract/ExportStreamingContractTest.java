package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号导出「流式 + 序列化在事务外」的防复发门禁（静态）。
 *
 * <p>背景（第三十八批取证）：导出曾把整份文档序列化为 {@code String} 再交给消息转换器编码，
 * 峰值堆 ≈ 2× 响应体且响应体无上限——实测 12.2MB 响应在 {@code -Xmx128m} 上直接
 * {@code OutOfMemoryError}（6.1MB 可完成）；同时序列化发生在 {@code @Transactional} 方法体内，
 * 连接在整个 JSON 生成期间被占用。修复后：事务只覆盖 DB 读取，序列化直接写响应输出流。
 *
 * <p>堆占用与连接占用都无法由常规断言观测（MockMvc 不产生真实分块响应、测试无法限制堆），
 * 故用源码形状门禁固化这条性质：一旦有人改回「整份缓冲」或把写流放回事务内，门禁即失败。
 */
class ExportStreamingContractTest {

    private static final String SERVICE = "server/src/main/java/com/intelligentresume/auth/service/AccountExportService.java";
    private static final String CONTROLLER = "server/src/main/java/com/intelligentresume/auth/controller/AuthController.java";

    @Test
    @DisplayName("导出链路不得整份缓冲：不出现 writeValueAsString，且直接写响应输出流")
    void exportPathNeverBuffersWholeDocument() throws Exception {
        String service = read(SERVICE);
        String controller = read(CONTROLLER);

        // 自检：门禁必须解析到目标代码，否则视为失效（防空转）
        assertTrue(service.contains("loadExportPayload") && service.contains("writePayloadAsJson"),
                "未在 AccountExportService 找到导出方法，门禁可能失效");
        assertTrue(controller.contains("/export"), "未在 AuthController 找到导出端点，门禁可能失效");

        assertFalse(service.contains("writeValueAsString"),
                "AccountExportService 出现 writeValueAsString：整份文档缓冲的峰值堆 ≈ 2× 响应体，"
                        + "12.2MB 响应在 -Xmx128m 上会直接 OutOfMemoryError，必须继续走流式写出");
        assertTrue(controller.contains("getOutputStream()"),
                "AuthController 的导出端点必须把 JSON 写入响应输出流（流式），而不是返回整份字符串");
    }

    @Test
    @DisplayName("序列化必须在事务外：写流方法不得带 @Transactional")
    void serializationHappensOutsideTransaction() throws Exception {
        String service = read(SERVICE);
        String controller = read(CONTROLLER);

        // 事务只应覆盖 DB 读取（loadExportPayload）；写流方法若被 @Transactional 覆盖，连接会在
        // JSON 生成期间被占用（多用户并发导出同时占连接 + 叠加堆，限流按 IP 互不约束）
        String transactionalPrefix = "@Transactional[\\s\\S]{0,300}?writePayloadAsJson";
        assertFalse(Pattern.compile(transactionalPrefix).matcher(service).find(),
                "writePayloadAsJson 不得被 @Transactional 覆盖：序列化必须在事务（连接）释放之后进行");
        assertFalse(Pattern.compile("@Transactional[\\s\\S]{0,300}?exportData").matcher(controller).find(),
                "控制器导出方法不得带 @Transactional：序列化不与 DB 连接同时占用");

        // 显式存在的正向约束：读取方法仍是只读事务
        assertTrue(Pattern.compile("@Transactional\\(readOnly = true\\)[\\s\\S]{0,300}?loadExportPayload")
                        .matcher(service).find(),
                "loadExportPayload 应保持 @Transactional(readOnly = true)（事务边界只覆盖 DB 读取）");
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
