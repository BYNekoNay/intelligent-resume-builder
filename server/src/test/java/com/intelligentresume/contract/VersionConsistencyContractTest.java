package com.intelligentresume.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号一致性门禁：同一版本号在仓库里有 **11 处独立副本**，且过去**没有任何机器判据**。
 *
 * <p>为什么必须有：只改一处会得到「看起来是新的、实际不是」的静默不一致 —— 例如
 * {@code pom.xml} 升到 0.4.0 但 {@code deploy-direct.remote.sh} 的 {@code JAR_NAME} 还是 0.3.0，
 * 部署会去找一个不存在的文件；而这类错误不会让任何测试变红。
 *
 * <p>jar 名尤其危险：{@code JAR_NAME}（脚本）、{@code ExecStart}（systemd）、
 * {@code pom} 产出的文件名**三者必须一致**，少改一处部署即断。
 *
 * <p>本门禁的判定方式是「收集全部副本的版本值，断言它们的集合大小恰好为 1」：
 * 这样既能在任一处不一致时变红，也不会因为将来新增一个副本（且版本相同）而误报。
 */
class VersionConsistencyContractTest {

    /** 合法版本号形态：x.y.z。 */
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyVersionCopyAgrees() throws Exception {
        Map<String, String> copies = new LinkedHashMap<>();

        // ① pom.xml 的项目版本（注意：parent 的 3.3.2 不是本项目版本）
        String pom = read("server/pom.xml");
        Matcher pomMatcher = Pattern.compile(
                        "<artifactId>intelligent-resume-server</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(pomMatcher.find(), "server/pom.xml 里找不到本项目的 <version>（结构变了？）");
        copies.put("server/pom.xml", pomMatcher.group(1));

        // ② SystemController 上报给前端的版本字面量
        copies.put("server/.../SystemController.java", firstGroup(
                read("server/src/main/java/com/intelligentresume/system/controller/SystemController.java"),
                "overallStatus\\(checks\\),\\s*\"([^\"]+)\""));

        // ③ 它的测试断言（字面量副本的第四处）
        copies.put("server/.../SystemControllerTest.java", firstGroup(
                read("server/src/test/java/com/intelligentresume/system/controller/SystemControllerTest.java"),
                "assertEquals\\(\"([^\"]+)\",\\s*response\\.data\\(\\)\\.version\\(\\)\\)"));

        // ④⑤ web 的 package.json 与 lock（lock 用 JSON 解析，避免误取依赖自身的 version）
        copies.put("web/package.json", jsonVersion(read("web/package.json")));
        copies.put("web/package-lock.json", lockVersion(read("web/package-lock.json")));

        // ⑥⑦⑧ pdf-service 的三个副本
        copies.put("pdf-service/package.json", jsonVersion(read("pdf-service/package.json")));
        copies.put("pdf-service/package-lock.json", lockVersion(read("pdf-service/package-lock.json")));
        copies.put("pdf-service/src/server.js", firstGroup(
                read("pdf-service/src/server.js"), "version:\\s*'([^']+)'"));

        // ⑨⑩⑪ jar 名三处：systemd 单元、部署脚本、部署手册
        copies.put("deploy/systemd/intelligent-resume-api.service", jarName(
                read("deploy/systemd/intelligent-resume-api.service")));
        copies.put("scripts/deploy-direct.remote.sh", jarName(
                read("scripts/deploy-direct.remote.sh")));
        copies.put("docs/DEPLOYMENT_DIRECT.md", jarName(read("docs/DEPLOYMENT_DIRECT.md")));

        // 副本清单条数本身也要被守住：悄悄删掉某一处检查会让门禁"看起来还在、其实少看了一个地方"
        assertEquals(11, copies.size(),
                "版本副本清单条数变了（新增或删除了一处副本）—— 必须同步更新本门禁的清单与预期条数："
                        + copies.keySet());

        // ---- 判定 ----
        Set<String> distinct = new LinkedHashSet<>(copies.values());
        assertEquals(1, distinct.size(),
                "版本号副本不一致 —— 同一版本必须处处相同："
                        + copies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList());

        String version = distinct.iterator().next();
        assertTrue(SEMVER.matcher(version).matches(),
                "版本号应为 x.y.z 形态，实际为 " + version + "（副本：" + copies + "）");

        // jar 名三处必须与版本号严格对应（防止「版本对了但 jar 名拼接方式变了」）
        for (Map.Entry<String, String> entry : copies.entrySet()) {
            if (entry.getKey().contains("jar") || entry.getKey().contains("service")
                    || entry.getKey().contains("remote.sh") || entry.getKey().contains("DIRECT")) {
                assertTrue(entry.getValue().equals(version),
                        entry.getKey() + " 的 jar 名版本与 pom 不一致：" + entry.getValue() + " vs " + version);
            }
        }
    }

    private static String read(String relative) throws Exception {
        Path target = repoFile(relative);
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    private static String firstGroup(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "未匹配到版本字面量（正则失效或结构变了）：" + regex);
        return matcher.group(1);
    }

    private static String jsonVersion(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.has("version"), "package.json 缺少 version 字段");
        return node.get("version").asText();
    }

    private static String lockVersion(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.has("version"), "package-lock.json 顶层缺少 version");
        JsonNode packages = node.path("packages");
        assertTrue(packages.has(""), "package-lock.json 缺少 packages[\"\"] 根条目");
        String top = node.get("version").asText();
        String root = packages.get("").path("version").asText();
        assertEquals(top, root, "package-lock.json 的顶层 version 与 packages[\"\"] 不一致");
        return top;
    }

    private static String jarName(String text) {
        Matcher matcher = Pattern.compile("intelligent-resume-server-([0-9][\\w.]*)\\.jar").matcher(text);
        assertTrue(matcher.find(), "未找到 intelligent-resume-server-X.jar 形式的 jar 名");
        return matcher.group(1);
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private static Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
