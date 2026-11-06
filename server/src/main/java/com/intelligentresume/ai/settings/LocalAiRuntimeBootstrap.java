package com.intelligentresume.ai.settings;

import com.intelligentresume.config.DesktopModeProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时把已保存的本地 AI 设置装进运行时覆盖 —— 让"上次保存的设置"在重启后依然生效。
 *
 * <p>【链路位置】应用启动完成事件（ApplicationReadyEvent）触发 → 读设置表 →
 * 逐行调 {@code LocalAiSettingService#publishToRuntime} 推给 {@code AiRuntimeOverlay}。</p>
 *
 * <p>【改坏它会怎样】去掉这一步，重启后设置页填着值、实际任务却用配置文件里的 key。
 * 只影响 desktop profile：在线部署 {@link DesktopModeProperties#isEnabled()} 为 false，直接返回。</p>
 *
 * <p>刻意不在 {@code CommandLineRunner} 里做：那时 Flyway/连接池可能还没稳定，
 * 而 {ApplicationReadyEvent} 之后读库是安全的。</p>
 *
 * <p>要点:</p>
 * <ul>
 *   <li>只在 desktop 模式下运行;在线部署直接返回,运行时覆盖保持为空(行为与未改造前一致)。</li>
 *   <li>遍历所有设置行而不是取第一条:正常只有一行(表上有唯一约束),遍历是为了容忍历史脏数据。</li>
 *   <li>多行时后处理的会覆盖先处理的运行时快照,顺序不确定 —— 这也是"不应存在多行"的原因。</li>
 * </ul>
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
