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
 * 静态资源交付契约门禁（静态，跨运行时）：服务 SPA 产物的 nginx 各层必须
 * ①开启 gzip 压缩文本类产物、②对**带内容哈希**的产物给长缓存、③让 SPA 回退入口保持
 * {@code no-cache}。
 *
 * <p>背景（第三十五批取证）：
 * <ul>
 *   <li>构建产物 64 个 js/css/svg 合计 **1001.1 KB → gzip 285.5 KB（3.51×）**（首屏入口
 *       5 个文件 457.4 KB → 139.8 KB），而三份服务静态资源的 nginx 配置**均无 gzip**
 *       ——首次访问按原始字节传输。</li>
 *   <li>容器版 `web/nginx.conf`（镜像实际打包，`web/Dockerfile` COPY 进 `/etc/nginx/conf.d/`）
 *       与其部署侧同步副本 `deploy/nginx/web.conf` **都缺**宿主机版 `deploy/nginx/host.conf`
 *       已有的「带内容哈希的构建产物可长期缓存」块：容器拓扑下 `/assets/index-&lt;hash&gt;.js`
 *       等 60+ 个资源被 `location /` 的 {@code Cache-Control: no-cache} 命中，浏览器每次导航
 *       都要逐个回源校验；两个部署拓扑行为漂移。</li>
 * </ul>
 * 与体积上限同属「数值散落在多份 nginx conf、既有测试全谱不可见」的类别，故用门禁固化。
 */
class StaticAssetDeliveryContractTest {

    /** 服务静态产物并反代 /api 的配置（edge 只反代、无 root，不在本清单内）。 */
    private static final String[] STATIC_SERVING_CONFS = {
            "web/nginx.conf",
            "deploy/nginx/web.conf",
            "deploy/nginx/host.conf"
    };

    @Test
    @DisplayName("服务静态产物的 nginx 必须开启 gzip 且覆盖 js/css（构建产物实测可压到 1/3.5）")
    void staticConfsEnableGzip() throws Exception {
        for (String conf : STATIC_SERVING_CONFS) {
            String content = read(conf);
            assertTrue(Pattern.compile("gzip\\s+on\\s*;").matcher(content).find(),
                    conf + " 未开启 gzip：构建产物（64 个 js/css/svg 合计 1001KB）按原始字节传输，"
                            + "首次访问多传约 715KB（gzip 后 285KB）");
            assertTrue(Pattern.compile("gzip_vary\\s+on\\s*;").matcher(content).find(),
                    conf + " 未声明 gzip_vary on：压缩后的响应缺少 Vary: Accept-Encoding，"
                            + "共享缓存可能把压缩版本发给不支持 gzip 的客户端");
            Matcher types = Pattern.compile("gzip_types\\s+([^;]+);").matcher(content);
            assertTrue(types.find(), conf + " 未声明 gzip_types（nginx 默认只压 text/html）");
            String list = types.group(1);
            for (String required : new String[]{"application/javascript", "text/css"}) {
                assertTrue(list.contains(required),
                        conf + " 的 gzip_types 缺少 " + required + "（当前：" + list.trim() + "）："
                                + "否则首屏入口的 script/style 仍按原始字节传输");
            }
        }
    }

    @Test
    @DisplayName("带内容哈希的产物长缓存，SPA 回退入口保持 no-cache（容器版与宿主机版一致）")
    void hashedAssetsAreCachedButEntryIsNot() throws Exception {
        for (String conf : STATIC_SERVING_CONFS) {
            String content = read(conf);

            Matcher assets = Pattern.compile("location\\s+~\\*\\s*\\\\\\.\\(js\\|css[^)]*\\)\\$?\\s*\\{([^}]*)\\}")
                    .matcher(content);
            assertTrue(assets.find(),
                    conf + " 缺少「带内容哈希的构建产物」location 块（形如 location ~* \\.(js|css|...)$）："
                            + "哈希产物会被下方的 Cache-Control: no-cache 命中，每次导航逐个回源校验");
            String block = assets.group(1);
            assertTrue(block.contains("immutable"),
                    conf + " 的哈希产物块未声明 immutable：内容哈希产物可安全长期缓存（当前块：" + block.trim() + "）");
            long maxAgeSeconds = maxAgeSeconds(block);
            assertTrue(maxAgeSeconds >= 30L * 24 * 3600,
                    conf + " 的哈希产物块有效期过短（" + maxAgeSeconds + " 秒）：内容哈希产物应缓存 ≥ 30 天");

            int rootLocation = content.indexOf("location / {");
            assertTrue(rootLocation >= 0, conf + " 缺少 SPA 回退的 location / 块");
            String rootBlock = content.substring(rootLocation);
            assertTrue(rootBlock.contains("no-cache"),
                    conf + " 的 SPA 回退入口未声明 no-cache：发版后客户端可能继续沿用旧 index.html，"
                            + "引用到已删除的旧哈希产物");
        }
    }

    /** 从 location 块中取缓存有效期秒数：优先 add_header 的 max-age，其次 expires 的天数。 */
    private long maxAgeSeconds(String block) {
        Matcher maxAge = Pattern.compile("max-age\\s*=\\s*(\\d+)").matcher(block);
        if (maxAge.find()) {
            return Long.parseLong(maxAge.group(1));
        }
        Matcher expires = Pattern.compile("expires\\s+(\\d+)([dhm])\\s*;").matcher(block);
        if (expires.find()) {
            long value = Long.parseLong(expires.group(1));
            return switch (expires.group(2)) {
                case "d" -> value * 24 * 3600;
                case "h" -> value * 3600;
                default -> value * 60;
            };
        }
        return 0L;
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
