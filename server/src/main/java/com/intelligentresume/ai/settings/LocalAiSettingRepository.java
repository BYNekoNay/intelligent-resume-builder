package com.intelligentresume.ai.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface LocalAiSettingRepository extends JpaRepository<LocalAiSetting, Long> {

    /** 每个本地用户只有一份设置（表上有唯一键）。 */
    Optional<LocalAiSetting> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
