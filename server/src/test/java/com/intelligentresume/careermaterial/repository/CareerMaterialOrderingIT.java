package com.intelligentresume.careermaterial.repository;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 职业资料排序契约锚定（第二十五批）。
 *
 * <p>career_material.updated_at 为毫秒精度，批量确认可在同一毫秒写入多条。AI 选材
 * 读路径（MaterialSelector 的 normal 截断、JobMaterialSelectionService 的候选排序）
 * 依赖本查询顺序；单键排序在并列时无稳定契约，会让「被丢弃的素材集合」run-to-run
 * 变化。本测试把三条资料的 updated_at 统一为同一时刻（插入顺序与期望相反），断言
 * 按 (updated_at desc, id desc) 返回更大的 id 在前。
 */
@SpringBootTest
@ActiveProfiles("test")
class CareerMaterialOrderingIT {

    @Autowired private UserRepository userRepository;
    @Autowired private CareerMaterialRepository materialRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("同毫秒并列时按 (updated_at desc, id desc) 返回，而不是插入顺序")
    void materials_tieBreakByIdDesc() {
        // 同一 Spring 上下文里的 H2 库在测试类间共享，用户名/邮箱需唯一
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("cm_order_" + suffix);
        user.setEmail("cm-order-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);

        List<Long> insertedIds = new ArrayList<>(List.of(
                seedMaterial(user.getId(), "最早写入"),
                seedMaterial(user.getId(), "第二条"),
                seedMaterial(user.getId(), "最后写入")));

        // 同一毫秒并列：模拟批量确认；H2 对并列单键排序会回退为插入顺序
        LocalDateTime sameInstant = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("UPDATE career_material SET updated_at = ? WHERE user_id = ?",
                sameInstant, user.getId());

        List<Long> returnedIds = materialRepository
                .findByUserIdOrderByUpdatedAtDescIdDesc(user.getId())
                .stream().map(CareerMaterial::getId).toList();

        Collections.reverse(insertedIds);
        assertEquals(insertedIds, returnedIds);
    }

    private Long seedMaterial(Long userId, String title) {
        CareerMaterial material = new CareerMaterial();
        material.setUserId(userId);
        material.setMaterialType(MaterialType.PROJECT_EXPERIENCE);
        material.setTitle(title);
        material.setContentJson(Map.of("role", "后端工程师"));
        material.setSourceText("原始文本 " + title);
        return materialRepository.saveAndFlush(material).getId();
    }
}