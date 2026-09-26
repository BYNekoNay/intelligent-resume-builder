package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.AuthSession;
import com.intelligentresume.auth.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证会话撤销服务:以独立事务(REQUIRES_NEW)持久化撤销标记。
 *
 * <p>为什么必须独立事务:AuthService.refresh() 标注了 @Transactional,
 * 而"旧 token 复用 → 撤销整族"与"令牌过期 → 标记会话"这两个分支
 * 都在写库之后立即抛出异常。若撤销与 refresh 共用同一事务,
 * 异常会导致整个事务回滚,撤销标记从未真正落库 ——
 * 攻击者重放旧 token 后,同族最新会话依然可用,防重放机制形同虚设
 * (缺陷修复记录:refresh 重放触发的族撤销被外层事务回滚吞掉)。
 * 因此撤销必须走独立事务提交,即使外层 refresh 事务随后回滚也要持久化。
 *
 * <p>实现说明:REQUIRES_NEW 不能通过 AuthService 内部 this 调用实现
 * (自调用不经过 Spring 代理,注解不生效),故撤销能力独立成 bean。
 */
@Service
public class AuthSessionRevocationService {

    private final AuthSessionRepository authSessionRepository;

    public AuthSessionRevocationService(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    /**
     * 撤销整个 token family(独立事务提交)。
     *
     * <p>用于 refresh 重放检测:即使调用方事务随后回滚,本撤销也必须持久化。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId, String reason) {
        List<AuthSession> family = authSessionRepository.findByTokenFamilyId(familyId);
        LocalDateTime now = LocalDateTime.now();
        for (AuthSession session : family) {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(now);
                session.setRevokeReason(reason);
            }
        }
        authSessionRepository.saveAll(family);
    }

    /**
     * 仅撤销单个会话(独立事务提交)。
     *
     * <p>用于 refresh 过期分支:标记之后同样会抛出异常,共用事务会导致标记被回滚,
     * 使每次过期重试都重复走该分支。按主键在新事务中重新加载,
     * 避免外层持久化上下文中受管实体的状态混入本事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeSession(Long sessionId, String reason) {
        authSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(LocalDateTime.now());
                session.setRevokeReason(reason);
                authSessionRepository.save(session);
            }
        });
    }
}
