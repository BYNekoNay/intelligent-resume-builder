package com.intelligentresume.auth.repository;

import com.intelligentresume.auth.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 用户表的数据访问。
 *
 * <p>注意软删语义：{@link User} 继承 {@code SoftDeletableEntity}，Hibernate 会给这些派生查询
 * 自动加上 {@code deleted_at IS NULL}。因此删号后的账号在这里「查不到」——
 * 需要读取已删账号（撤销、清理、恢复）的场景必须换原生 SQL 或另加忽略过滤条件的查询。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按用户名精确查（登录时第一步）；不做大小写归一。 */
    Optional<User> findByUsername(String username);

    /** 按邮箱精确查（登录的第二步兜底）。 */
    Optional<User> findByEmail(String email);

    /**
     * 带行锁（PESSIMISTIC_WRITE）读取：删号流程用它串行化同一用户的并发删号/恢复。
     * 必须在事务内调用，否则方法返回即释放锁，失去互斥意义。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 注册查重。这是「先查后写」的乐观检查，并发注册仍可能同时通过、在提交时撞唯一键——
     * 那种情况由 {@code GlobalExceptionHandler} 的 DataIntegrityViolation 分支映射为 40901。
     */
    boolean existsByUsername(String username);

    /** 邮箱查重，语义同 {@link #existsByUsername(String)}。 */
    boolean existsByEmail(String email);
}
