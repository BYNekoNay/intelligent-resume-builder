package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.AuthSession;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.auth.dto.ChangeEmailRequest;
import com.intelligentresume.auth.dto.ChangePasswordRequest;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.dto.UpdateProfileRequest;
import com.intelligentresume.auth.repository.AuthSessionRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.retention.AccountDeletionJob;
import com.intelligentresume.retention.AccountDeletionJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 认证领域服务:注册 / 登录 / 刷新 / 退出。
 *
 * <p>【小白导览】①解决什么业务问题：账号的「进门与出门」——注册建号、密码换令牌、令牌续期、
 * 登出、改密/改邮箱、注销账号，全站只有这里直接决定「这个人是不是本人」。
 * ②在链路哪里：AuthController（HTTP 入口）把请求转交给它；它向下调 {@link TokenService}（造/验令牌）、
 * UserRepository 与 AuthSessionRepository（读写账号表和会话表），产出是一对令牌（access + refresh）
 * 交回前端。③改坏它用户会看到什么：轻则登录不上、刷新一直失败被踢回登录页；重则是安全事故——
 * 已注销/停用的账号还能操作、改密码后旧设备不掉线、被盗的刷新令牌检测不出来。
 *
 * <p>术语（后文反复出现）：access token（访问令牌，有效期短，前端每个请求都带上）；
 * refresh token（刷新令牌，有效期长，只用来换新的 access token）；令牌轮换（每次刷新换一对新令牌、
 * 旧的立即作废——被盗用能及时发现）；令牌族（一次登录派生出的整条轮换链，共享一个 familyId，
 * 族内任一旧令牌被复用就整族撤销）；BCrypt（一种故意很慢的密码散列算法，库里只存散列不存明文）；
 * 软删除（不物理删行，只打 deleted_at 标记，查询时自动过滤）。
 *
 * <p>关键约定:
 * <ul>
 *     <li>refresh token 原文不落库,仅保存 SHA-256 摘要。</li>
 *     <li>每个用户一次刷新会生成新的 token family;任何旧 token 复用都会撤销整族。
 *     撤销经由 {@link AuthSessionRevocationService} 以独立事务(REQUIRES_NEW)持久化,
 *     避免随 refresh 自身的事务回滚而丢失。</li>
 *     <li>密码使用 {@link PasswordEncoder}(BCrypt) 散列。</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AiConsentService aiConsentService;
    private final AiTaskRepository aiTaskRepository;
    private final ExportTaskRepository exportTaskRepository;
    private final AuthSessionRevocationService authSessionRevocationService;
    private final ActiveUserCache activeUserCache;
    private final AccountDeletionJobRepository deletionJobRepository;
    /** 删除撤销窗口（天）。docs/04 §7.1 的承诺口径：7 天窗口 + 窗口结束后 30 天内完成清理。 */
    private final int deletionGraceDays;

    public AuthService(UserRepository userRepository,
                       AuthSessionRepository authSessionRepository,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder,
                       AiConsentService aiConsentService,
                       AiTaskRepository aiTaskRepository,
                       ExportTaskRepository exportTaskRepository,
                       AuthSessionRevocationService authSessionRevocationService,
                       ActiveUserCache activeUserCache,
                       AccountDeletionJobRepository deletionJobRepository,
                       @Value("${app.retention.account-deletion.grace-days:7}") int deletionGraceDays) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.aiConsentService = aiConsentService;
        this.aiTaskRepository = aiTaskRepository;
        this.exportTaskRepository = exportTaskRepository;
        this.authSessionRevocationService = authSessionRevocationService;
        this.activeUserCache = activeUserCache;
        this.deletionJobRepository = deletionJobRepository;
        this.deletionGraceDays = deletionGraceDays;
    }

    /** 注册：先做「用户名或邮箱被占用」的统一提示，再创建账号并直接签发令牌（等价注册即登录）。 */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        // 统一冲突消息,避免区分"用户名/邮箱"导致账号枚举
        if (userRepository.existsByUsername(request.username())
                || userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.CONFLICT, "注册信息已被占用:用户名或邮箱不可用");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.username());
        userRepository.save(user);

        return issueNewFamily(user, "register");
    }

    /**
     * 登录：用户名或邮箱 + 密码，成功后新开一个令牌族。
     *
     * <p>顺序是先查账号、再判状态、最后验口令（BCrypt 比对故意放在状态判断之后，
     * 避免为不存在/已停用的账号付出散列成本）。密码错误与账号不存在共用同一句文案。
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .or(() -> userRepository.findByEmail(request.username()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误"));

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            // 删除撤销期内的账号（D2 阶段 3）给专用错误码，登录页据此展示「恢复账号」入口；
            // 其余停用账号维持原拒绝语义。恢复动作必须走凭据验证的 /deletion/restore。
            if (hasActiveDeletionWindow(user.getId())) {
                throw new BusinessException(ErrorCode.ACCOUNT_DELETION_PENDING,
                        "账号处于删除撤销期（7 天内可凭密码恢复），请使用恢复入口重新激活账号");
            }
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误");
        }

        return issueNewFamily(user, "login");
    }

    /**
     * 删除撤销（决策 D2 阶段 3）：凭用户名/邮箱 + 密码恢复处于撤销窗口内的账号。
     *
     * <p>恢复 = 账号回 ACTIVE、清除 deletedAt、全部 PENDING 删号任务转 CANCELLED，并签发全新
     * 会话（等价重新登录）。删号时已执行的不可逆动作**不回滚**：AI 授权需重新同意、已取消的
     * AI/导出任务不复活、原会话需重新登录 —— 恢复入口的界面文案必须写明。
     *
     * <p>凭据错误统一报「账号或密码错误」（防账号枚举）；凭据正确但无有效撤销窗口时才区分
     * 「不在撤销期（40903）」。
     */
    @Transactional
    public TokenResponse restoreDeletion(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .or(() -> userRepository.findByEmail(request.username()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误");
        }

        AccountDeletionJob job = deletionJobRepository
                .findFirstByUserIdAndStatusOrderByIdDesc(user.getId(), AccountDeletionJob.Status.PENDING)
                .filter(pending -> pending.getCancelUntil().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_DELETION_WINDOW_EXPIRED,
                        "该账号不在删除撤销期内（从未请求删除，或撤销窗口已结束）"));

        // 标记撤销（正常恰好一行 PENDING；CANCELLED 行留作审计）
        for (AccountDeletionJob pending : deletionJobRepository
                .findByUserIdAndStatus(user.getId(), AccountDeletionJob.Status.PENDING)) {
            pending.setStatus(AccountDeletionJob.Status.CANCELLED);
            pending.setCompletedAt(LocalDateTime.now());
            deletionJobRepository.save(pending);
        }

        user.setStatus(User.UserStatus.ACTIVE);
        user.setDeletedAt(null);
        userRepository.save(user);
        activeUserCache.evict(user.getId());
        return issueNewFamily(user, "deletion_restored");
    }

    private boolean hasActiveDeletionWindow(Long userId) {
        return deletionJobRepository
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, AccountDeletionJob.Status.PENDING)
                .map(job -> job.getCancelUntil().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    /**
     * 用 refresh token 旋转出新的 access + refresh。
     * 任一旧 token 复用都会撤销整族并要求重新登录。
     *
     * <p>【小白导览 · 五步看懂刷新】①把前端带来的 refresh token 算出摘要，按摘要去会话表找对应行
     * （库里不存原文，只能按摘要比对）；②该行已撤销 → 说明这个旧令牌被人用过第二次了（重放），
     * 撤销整个令牌族并拒绝——这是防被盗的核心机关；③已过期 → 标记后拒绝；
     * ④用一次原子 UPDATE（CAS，比较并交换：只有行还是「未撤销」状态才翻转成功）把旧会话置为
     * rotated，翻转失败同样按重放处理——保证并发刷新时绝不会有两个新令牌同时有效；
     * ⑤签发一对全新令牌，归属同一个令牌族，旧会话保留作审计。
     * 顺序不能换：必须先判重放/过期再轮换，否则重放者能先偷走新令牌。
     */
    @Transactional
    public TokenResponse refresh(String presentedRefreshToken, String userAgent, String ip) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "缺少刷新令牌");
        }
        String presentedHash = tokenService.hashToken(presentedRefreshToken);

        AuthSession session = authSessionRepository.findByRefreshTokenHash(presentedHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌不存在"));

        // 旧令牌被复用 → 撤销整族。
        // 撤销必须走独立事务(REQUIRES_NEW):本方法事务随后会因下方异常回滚,
        // 若撤销在同一事务内执行,回滚会把撤销一并吞掉,族内最新会话依然可用。
        if (session.getRevokedAt() != null) {
            authSessionRevocationService.revokeFamily(session.getTokenFamilyId(), "refresh_reuse_detected");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌已失效,请重新登录");
        }

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            // 过期标记同样必须在独立事务中持久化:标记之后立即抛异常,
            // 共用事务会导致标记被回滚,每次过期重试都会重复走此分支。
            authSessionRevocationService.revokeSession(session.getId(), "expired");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌已过期");
        }

        // 原子轮换(CAS):并发刷新同一 token 时,只有先到者能把旧会话从「未撤销」翻转为「已撤销」;
        // 后到者(含重放)拿 0 → 视同复用,撤销整族并要求重新登录 —— 杜绝"两个后继 token 同时有效"。
        // 安全优先取舍:多标签页并发刷新会命中该分支(需重新登录),属既定口径。
        int rotated = authSessionRepository.revokeIfActive(session.getId(), LocalDateTime.now(), "rotated");
        if (rotated == 0) {
            authSessionRevocationService.revokeFamily(session.getTokenFamilyId(), "refresh_reuse_detected");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌已失效,请重新登录");
        }

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // 直接发新一对(保持原 family)
        String newRefresh = tokenService.issueRefreshToken();
        AuthSession fresh = new AuthSession();
        fresh.setUserId(user.getId());
        fresh.setTokenFamilyId(session.getTokenFamilyId());
        fresh.setRefreshTokenHash(tokenService.hashToken(newRefresh));
        fresh.setIssuedAt(LocalDateTime.now());
        fresh.setExpiresAt(LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds()));
        fresh.setUserAgent(userAgent);
        fresh.setIpAddress(ip);
        authSessionRepository.save(fresh);

        return tokensFor(user, newRefresh);
    }

    /** 登出单个会话：令牌缺失直接成功；已撤销的会话保留原 revoke_reason，不覆盖取证信息。 */
    @Transactional
    public void logout(String presentedRefreshToken) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            return;
        }
        String hash = tokenService.hashToken(presentedRefreshToken);
        Optional<AuthSession> maybe = authSessionRepository.findByRefreshTokenHash(hash);
        maybe.ifPresent(session -> {
            // 已撤销的会话保留原 revokeReason(如 refresh_reuse_detected),不覆盖取证信息
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(LocalDateTime.now());
                session.setRevokeReason("logout");
                authSessionRepository.save(session);
            }
        });
    }

    /** 撤销该用户全部未失效会话：统一使用同一个 revokedAt，便于审计「一次全端登出」。 */
    @Transactional
    public void logoutAll(Long userId) {
        List<AuthSession> active = authSessionRepository.findByUserIdAndRevokedAtIsNull(userId);
        LocalDateTime now = LocalDateTime.now();
        for (AuthSession session : active) {
            session.setRevokedAt(now);
            session.setRevokeReason("logout_all");
        }
        authSessionRepository.saveAll(active);
    }

    /**
     * 删号第一步（可撤销）：行锁串行化同一用户，置 DISABLED + deletedAt、撤回 AI 授权、
     * 取消进行中的 AI/导出任务、撤销全部会话，并落一条 PENDING 任务（窗口内可恢复）。
     *
     * <p>物理删除不在这里发生：{@code AccountPurgeService} 在 {@code cancel_until} 之后才级联硬删。
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        user.setStatus(User.UserStatus.DISABLED);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
        aiConsentService.withdrawIfGranted(userId);
        LocalDateTime now = LocalDateTime.now();
        aiTaskRepository.cancelActiveByUserId(userId, "Account deleted", now);
        exportTaskRepository.failActiveByUserId(userId, "Account deleted", now);
        logoutAll(userId);
        // 决策 D2 阶段 3：删号进入撤销窗口 —— 数据保留 grace-days 天，窗口内可凭密码恢复；
        // 窗口结束后由 AccountPurgeService 级联硬删。已有 PENDING 行则幂等跳过（重复请求不重建）。
        if (deletionJobRepository
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, AccountDeletionJob.Status.PENDING)
                .isEmpty()) {
            AccountDeletionJob job = new AccountDeletionJob();
            job.setUserId(userId);
            job.setStatus(AccountDeletionJob.Status.PENDING);
            job.setRequestedAt(now);
            job.setCancelUntil(now.plusDays(deletionGraceDays));
            deletionJobRepository.save(job);
        }
        // 「删号即失效」（#6）:用户状态缓存必须在**事务提交后**清除。
        // 提交前清除存在竞态:并发请求可能回读到未提交的 ACTIVE 并重新缓存,
        // 令失效延迟一个 TTL。无事务上下文（如单测直调）时立即清除。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    activeUserCache.evict(userId);
                }
            });
        } else {
            activeUserCache.evict(userId);
        }
    }

    /** 读当前用户；userId 为空或查不到都按 401（已软删的账号在这里也查不到）。 */
    public CurrentUserResponse currentUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName());
    }

    /** 只更新展示名（trim 后落库）；其余字段各有专门入口，不从这里改。 */
    @Transactional
    public CurrentUserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        user.setDisplayName(request.displayName().trim());
        userRepository.save(user);
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName());
    }

    /**
     * 改邮箱：先验当前密码（401），再判新邮箱是否被他人占用（409），落库后撤销全部会话。
     * 邮箱统一存小写——查重与写入必须使用同一个归一化值，否则大小写差异会绕过占用判断（取决于库排序规则）。
     */
    @Transactional
    public void changeEmail(Long userId, ChangeEmailRequest request) {
        User user = requireCurrentPassword(userId, request.currentPassword());
        String email = request.email().trim().toLowerCase();
        if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已被占用");
        }
        user.setEmail(email);
        userRepository.save(user);
        logoutAll(userId);
    }

    /**
     * 改密码：先验当前密码（401），重新 BCrypt 散列后落库，并撤销全部会话。
     * 这里刻意不校验「新旧密码是否相同」——重复设置同一密码是允许的。
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireCurrentPassword(userId, request.currentPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        logoutAll(userId);
    }

    // ----------------------------------------------------------------
    // private helpers
    // ----------------------------------------------------------------

    /**
     * 注册/登录/删除恢复共用的签发入口：新建一个令牌族（familyId）并写入第一行会话。
     *
     * <p>副作用：{@code session.user_agent} 在这里存的是**签发原因**（register/login/deletion_restored），
     * 不是浏览器 UA——UA 只在 refresh 轮换时写入。改动该列语义前需同步排查审计/桌面端读取方。
     */
    private TokenResponse issueNewFamily(User user, String reason) {
        String familyId = tokenService.newTokenFamilyId();
        String refresh = tokenService.issueRefreshToken();

        AuthSession session = new AuthSession();
        session.setUserId(user.getId());
        session.setTokenFamilyId(familyId);
        session.setRefreshTokenHash(tokenService.hashToken(refresh));
        session.setIssuedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds()));
        session.setUserAgent(reason);
        authSessionRepository.save(session);

        return tokensFor(user, refresh);
    }

    public void verifyCurrentPassword(Long userId, String currentPassword) {
        requireCurrentPassword(userId, currentPassword);
    }

    /**
     * 再认证：查用户并比对当前密码；任一失败都是 401。
     * 供导出等「必须证明是本人」的入口复用，不要各自再写一份口令比对。
     */
    private User requireCurrentPassword(Long userId, String currentPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "当前密码不正确");
        }
        return user;
    }

    private TokenResponse tokensFor(User user, String refresh) {
        String access = tokenService.issueAccessToken(user.getId(), user.getUsername());
        return new TokenResponse(access, tokenService.getAccessTokenTtlSeconds(), refresh);
    }

    /** 提供无参重载,避免 controller 在没有 userId 时绕过 SecurityContext。 */
    public CurrentUserResponse currentUser() {
        throw new BusinessException(ErrorCode.INTERNAL,
                "AuthService.currentUser() 必须由 Controller 显式传入 userId");
    }
}
