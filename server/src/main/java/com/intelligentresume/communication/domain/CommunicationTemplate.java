package com.intelligentresume.communication.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 沟通模板。内置模板 user_id=NULL 且 is_system=1（只读）；
 * 自定义模板 user_id 归属当前用户、is_system=0。
 *
 * <p>{@code @DynamicUpdate}：只更新脏字段，避免内容更新把 {@code usage_count}
 * 的并发自增（原子 UPDATE）按加载时的旧值写回、丢掉增量。
 */
@Entity
@Table(name = "communication_template")
@DynamicUpdate
public class CommunicationTemplate extends BaseEntity {

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene", nullable = false, length = 32)
    private TemplateScene scene;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 32)
    private CommunicationType templateType;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_language", nullable = false, length = 16)
    private CommunicationOutputLanguage outputLanguage = CommunicationOutputLanguage.ZH_CN;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    /**
     * 乐观锁版本（ideation #73）：自定义模板的并发内容更新不再「最后写入覆盖」，
     * 冲突时由全局处理器映射 40901。使用计数走原子自增（不经过本版本）。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public TemplateScene getScene() { return scene; }
    public void setScene(TemplateScene scene) { this.scene = scene; }
    public CommunicationType getTemplateType() { return templateType; }
    public void setTemplateType(CommunicationType templateType) { this.templateType = templateType; }
    public CommunicationOutputLanguage getOutputLanguage() { return outputLanguage; }
    public void setOutputLanguage(CommunicationOutputLanguage outputLanguage) { this.outputLanguage = outputLanguage; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public boolean isSystem() { return isSystem; }
    public void setSystem(boolean system) { isSystem = system; }
    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
}
