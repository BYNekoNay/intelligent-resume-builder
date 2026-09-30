package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器交付路径的资源边界门禁（静态，跨运行时）。
 *
 * <p>背景（第三十九批取证）：同一宿主上三条交付路径的资源边界并不一致——
 * <ul>
 *   <li>systemd 路径（{@code deploy/systemd/intelligent-resume-api.service}）显式定
 *       {@code -Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m}，且文档记载「JVM 堆已从 1024m 降到 -Xmx768m」
 *       正是为适配 3.6GiB 宿主；</li>
 *   <li>ip-test 容器路径（{@code docker-compose.ip-test.yml}，以 {@code -f prod -f ip-test} 叠加使用）
 *       给 4 个服务都写了 {@code mem_limit}，API 另有 {@code JAVA_TOOL_OPTIONS: -Xms128m -Xmx512m}；</li>
 *   <li>**生产容器路径**（{@code docker-compose.prod.yml}）此前**没有任何 {@code mem_limit}、没有
 *       {@code JAVA_TOOL_OPTIONS}、也没有日志上限**。</li>
 * </ul>
 * 后果：未指定 {@code -Xmx} 时 JVM 按「可用内存的 25%」默认（本机实测 15.71GiB → {@code MaxHeapSize=4219469824}
 * 字节），3.6GiB 宿主上等价 **≈922MB**，高于 systemd 路径刻意压到的 768m；容器也没有内存上限，
 * 含 Chromium 的 pdf-service 可无界占用；而容器日志（docker 默认 json-file 驱动）**不轮转**，
 * 与 systemd 路径「日志走 journald（自带轮转）」形成不对称。
 */
class ComposeResourceBoundsContractTest {

    private static final String PROD_COMPOSE = "deploy/docker-compose.prod.yml";
    private static final String IP_TEST_COMPOSE = "deploy/docker-compose.ip-test.yml";
    private static final String SYSTEMD_UNIT = "deploy/systemd/intelligent-resume-api.service";

    /** mem_limit 之上为元空间/线程/直接内存留出的余量（MB）。 */
    private static final long NON_HEAP_HEADROOM_MB = 256;

    @Test
    @DisplayName("生产 compose 的每个服务都必须有内存上限与有界日志（防占满宿主内存/磁盘）")
    void productionComposeServicesAreBounded() throws Exception {
        Map<String, Object> services = services(PROD_COMPOSE);
        assertTrue(services.size() >= 5, PROD_COMPOSE + " 解析到的服务过少（" + services.size() + "），门禁可能失效");

        Map<String, String> problems = new TreeMap<>();
        services.forEach((name, raw) -> {
            Map<String, Object> service = asMap(raw);
            if (memoryLimitMb(service) <= 0) {
                problems.put(name, "缺少 mem_limit（或 deploy.resources.limits.memory）：容器可无界占用宿主内存，"
                        + "而文档记载宿主内存偏紧（3.6GiB）");
            }
            String loggingProblem = loggingProblem(service);
            if (loggingProblem != null) {
                problems.put(name, loggingProblem);
            }
        });
        assertTrue(problems.isEmpty(), PROD_COMPOSE + " 存在无界服务：\n" + problems);
    }

    @Test
    @DisplayName("叠加层（-f prod -f ip-test）不得造出无界服务：声明的上限必须有界且彼此自洽")
    void overlayOverridesStayBounded() throws Exception {
        Map<String, Object> services = services(IP_TEST_COMPOSE);
        assertTrue(services.size() >= 4, IP_TEST_COMPOSE + " 解析到的服务过少（" + services.size() + "），门禁可能失效");

        Map<String, String> problems = new TreeMap<>();
        services.forEach((name, raw) -> {
            Map<String, Object> service = asMap(raw);
            if (service.containsKey("mem_limit") && memoryLimitMb(service) <= 0) {
                problems.put(name, "mem_limit 取值无法解析： " + service.get("mem_limit"));
            }
            if (service.containsKey("logging")) {
                String loggingProblem = loggingProblem(service);
                if (loggingProblem != null) {
                    problems.put(name, loggingProblem);
                }
            }
        });
        assertTrue(problems.isEmpty(), IP_TEST_COMPOSE + " 存在无界覆盖（日志上限继承基础文件，一旦自行声明就必须有界）：\n"
                + problems);
    }

    @Test
    @DisplayName("api 堆上限与 systemd 路径一致，且容器内存上限留出元空间余量（两条路径同为 768m）")
    void apiHeapCapMatchesSystemdUnit() throws Exception {
        long systemdHeapMb = heapMb(read(SYSTEMD_UNIT));
        assertTrue(systemdHeapMb > 0, "systemd 单元应声明 -Xmx");

        assertApiBounds(PROD_COMPOSE, systemdHeapMb,
                "同一宿主的容器路径与 systemd 路径应使用同一个堆上限，否则两条交付路径的内存行为不一致");
        // 叠加层把堆压到更小值（同宿主的验收环境），也必须留出元空间余量
        assertApiBounds(IP_TEST_COMPOSE, 0, null);
    }

    private void assertApiBounds(String compose, long expectedHeapMb, String heapMismatchMessage) throws Exception {
        Map<String, Object> apiService = asMap(services(compose).get("api"));
        String javaOptions = String.valueOf(asMap(apiService.get("environment")).getOrDefault("JAVA_TOOL_OPTIONS", ""));

        long heapMb = heapMb(javaOptions);
        assertTrue(heapMb > 0,
                compose + " 的 api 服务必须显式声明 JAVA_TOOL_OPTIONS 且含 -Xmx：未指定时 JVM 按可用内存的 25% 默认"
                        + "（实测 15.71GiB → MaxHeapSize=4219469824 字节），3.6GiB 宿主上等价 ≈922MB，"
                        + "高于 systemd 路径刻意压到的 768m");
        if (expectedHeapMb > 0) {
            assertEquals(expectedHeapMb, heapMb, heapMismatchMessage
                    + "（" + compose + " = " + heapMb + "m vs systemd = " + expectedHeapMb + "m）");
        }

        long memLimitMb = memoryLimitMb(apiService);
        assertTrue(memLimitMb >= heapMb + NON_HEAP_HEADROOM_MB,
                compose + " 的 api mem_limit（" + memLimitMb + "m）必须 ≥ 堆上限（" + heapMb
                        + "m）+ 元空间/线程余量 " + NON_HEAP_HEADROOM_MB + "m，否则 JVM 未达堆上限就被 OOMKilled");
    }

    /** 有界日志：必须声明 logging.options 的 max-size 与 max-file；有界返回 null，否则返回问题描述。 */
    private String loggingProblem(Map<String, Object> service) {
        Map<String, Object> logging = asMap(service.get("logging"));
        if (logging.isEmpty()) {
            return "缺少 logging 配置：docker 默认 json-file 驱动不轮转，容器日志无上限累积";
        }
        Map<String, Object> options = asMap(logging.get("options"));
        if (!options.containsKey("max-size") || !options.containsKey("max-file")) {
            return "logging.options 必须同时声明 max-size 与 max-file（当前：" + options.keySet() + "）";
        }
        return null;
    }

    /** 解析 {@code -Xmx768m} 的兆字节取值；未声明返回 0。 */
    private long heapMb(String javaOptions) {
        Matcher matcher = Pattern.compile("-Xmx(\\d+)([mMgG])").matcher(javaOptions == null ? "" : javaOptions);
        if (!matcher.find()) {
            return 0L;
        }
        long value = Long.parseLong(matcher.group(1));
        return matcher.group(2).equalsIgnoreCase("g") ? value * 1024 : value;
    }

    /** 解析容器内存上限（{@code mem_limit: 1200m} 或 {@code deploy.resources.limits.memory}）；未声明返回 0。 */
    private long memoryLimitMb(Map<String, Object> service) {
        Object limit = service.get("mem_limit");
        if (limit == null) {
            Map<String, Object> deploy = asMap(service.get("deploy"));
            limit = asMap(asMap(deploy.get("resources")).get("limits")).get("memory");
        }
        if (limit == null) {
            return 0L;
        }
        Matcher matcher = Pattern.compile("(\\d+)\\s*([mMgG]?)").matcher(String.valueOf(limit).trim());
        if (!matcher.matches()) {
            return 0L;
        }
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase()) {
            case "g" -> value * 1024;
            case "m" -> value;
            default -> value / (1024 * 1024);
        };
    }

    private Map<String, Object> services(String compose) throws Exception {
        Object services = loadYaml(compose).get("services");
        assertTrue(services instanceof Map, compose + " 缺少 services 段");
        return asMap(services);
    }

    private Map<String, Object> loadYaml(String relative) throws Exception {
        try (InputStream input = Files.newInputStream(repoFile(relative))) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, relative + " 顶层应为映射");
            return asMap(loaded);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private String read(String relative) throws Exception {
        return Files.readString(repoFile(relative), StandardCharsets.UTF_8);
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
