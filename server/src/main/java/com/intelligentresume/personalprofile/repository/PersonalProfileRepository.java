package com.intelligentresume.personalprofile.repository;

import com.intelligentresume.personalprofile.domain.PersonalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 个人档案数据访问：一人一份，所有查询都按 {@code userId} 走。 */
public interface PersonalProfileRepository extends JpaRepository<PersonalProfile, Long> {

    /** 档案可能不存在（用户从未保存过），调用方必须处理空值；并发 upsert 的锁在 service 层的 findByIdForUpdate。 */
    Optional<PersonalProfile> findByUserId(Long userId);
}
