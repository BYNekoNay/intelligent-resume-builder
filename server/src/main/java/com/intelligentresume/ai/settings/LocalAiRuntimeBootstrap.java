package com.intelligentresume.ai.settings;

import com.intelligentresume.config.DesktopModeProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时把已保存的本地 AI 设置装进运行时覆盖。
 *
 * <p>没有这一步，"上次保存的设置"在重启后就失效了 —— 用户会看到设置页填着值、实际任务却用配置文件里的
 * key。只影响 desktop profile：在线部署 {@link DesktopModeProperties#isEnabled()} 为 false，直接返回。
 *
 * <p>刻意不在 {@code CommandLineRunner} 里做：那时 Flyway/连接池可能还没稳定，
 * 而 {ApplicationReadyEvent} 之后读库是安全的。
 */
@Component
class LocalAiRuntimeBootstrap {

    private final DesktopModeProperties properties;
    private final LocalAiSettingRepository repository;
    private final LocalAiSettingService service;

    LocalAiRuntimeBootstrap(DesktopModeProperties properties,
                            LocalAiSettingRepository repository,
                            LocalAiSettingService service) {
        this.properties = properties;
        this.repository = repository;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadSavedSetting() {
        if (!properties.isEnabled()) {
            return;
        }
        // 桌面工作区只有一个本地用户，正常只有一行；遍历是为了容忍历史脏数据而不是假设唯一
        repository.findAll().forEach(service::publishToRuntime);
    }
}
