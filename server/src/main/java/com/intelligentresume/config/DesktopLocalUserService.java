package com.intelligentresume.config;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 桌面本地工作区的恒定本地用户（ADR-012 D3）。
 *
 * <p>桌面端免登录的实现方式**不是**「把受保护端点改成放行」——那需要逐个改 27 处取用点，
 * 漏一处就是越权。这里的做法是：启动时幂等地准备一个恒定用户，之后由认证过滤器注入它的 ID，
 * 使既有全部 {@code currentUserId} 取用点**零改动**。
 *
 * <p>该用户：
 * <ul>
 *   <li>口令是启动时生成的随机值（BCrypt 存储），用户永远无法用它登录，只是不给 {@code password_hash} 留空；</li>
 *   <li>状态恒为 ACTIVE，保证 {@code ActiveUserCache} 与「删号即失效」语义在桌面端不误伤；</li>
 *   <li>ID 在启动时确定并缓存在内存中，避免依赖自增主键的具体取值。</li>
 * </ul>
 */
@Service
public class DesktopLocalUserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopLocalUserService.class);

    /** 本地用户的用户名。桌面端只有一个用户，不与任何在线账号同名。 */
    public static final String LOCAL_USERNAME = "local-desktop";

    private static final String LOCAL_EMAIL = "local-desktop@localhost.invalid";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final DesktopModeProperties properties;
    private final Environment environment;

    /** 启动后确定；未启用桌面模式时为 null。 */
    private volatile Long localUserId;

    public DesktopLocalUserService(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   TokenService tokenService,
                                   DesktopModeProperties properties,
                                   Environment environment) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }
        requireLoopbackBinding();
        Path dataDir = Path.of(properties.getDataDir()).toAbsolutePath();
        Files.createDirectories(dataDir);

        User user = userRepository.findByUsername(LOCAL_USERNAME).orElseGet(this::createLocalUser);
        // 桌面端的用户恒为可用：若此前被置为 DISABLED（例如误触了删号流程），这里恢复它。
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            user.setStatus(User.UserStatus.ACTIVE);
            userRepository.save(user);
        }
        this.localUserId = user.getId();
        log.info("桌面本地工作区已就绪：本地用户 {} (id={})，数据目录 {}",
                LOCAL_USERNAME, localUserId, dataDir);
    }

    /**
     * 安全护栏：桌面模式**必须**只绑回环地址。
     *
     * <p>原因：桌面模式下认证过滤器无条件注入本地用户（免登录），因此一旦绑定到非回环地址，
     * 局域网甚至公网都能直接读写本地工作区 —— 那等于开了一个**无认证的远程接口**。
     * 与其依赖使用者记得配置对，不如让不安全的配置**起不来**。
     */
    private void requireLoopbackBinding() {
        String address = environment.getProperty("server.address", "127.0.0.1");
        String normalized = address == null ? "" : address.trim().toLowerCase();
        boolean loopback = "127.0.0.1".equals(normalized)
                || "localhost".equals(normalized)
                || "::1".equals(normalized)
                || normalized.startsWith("127.");
        if (!loopback) {
            throw new IllegalStateException(
                    "桌面本地工作区必须只绑定回环地址（server.address=" + address + " 被拒绝）。"
                            + "桌面模式不做令牌校验，绑定到 0.0.0.0 会让局域网/公网直接读写本地工作区，"
                            + "等同于开了一个无认证的远程接口。请改回 127.0.0.1。");
        }
        log.info("桌面模式绑定检查通过：server.address={}", address);
    }

    private User createLocalUser() {
        User user = new User();
        user.setUsername(LOCAL_USERNAME);
        user.setEmail(LOCAL_EMAIL);
        user.setDisplayName("本机用户");
        // 随机口令：本地端不提供登录入口，这个值只用于满足非空约束，任何人都无法用它登录。
        user.setPasswordHash(passwordEncoder.encode("desktop-" + UUID.randomUUID()));
        user.setStatus(User.UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** 是否处于桌面本地模式。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 本地用户 ID；未启用桌面模式时为 null。 */
    public Long localUserId() {
        return localUserId;
    }

    /**
     * 为本地用户签发访问令牌。
     *
     * <p>桌面端的前端沿用在线版的启动流程（先 refresh 再拉当前用户），因此这里提供一条
     * 无需 refresh cookie 即可取到令牌的路径 —— 这正是「免登录」在协议层的落点。
     */
    public TokenResponse issueToken() {
        Long userId = localUserId;
        if (userId == null) {
            throw new IllegalStateException("桌面本地用户尚未初始化（enabled=" + properties.isEnabled() + "）");
        }
        String accessToken = tokenService.issueAccessToken(userId, LOCAL_USERNAME);
        return new TokenResponse(accessToken, tokenService.getAccessTokenTtlSeconds(), null);
    }
}
