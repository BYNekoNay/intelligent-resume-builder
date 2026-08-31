-- V24: 补齐内置模板 EN 变体（FOLLOW_UP / SALARY 场景的 OPENING_MESSAGE 载体）
-- 兼容 MySQL 5.7 / H2 MySQL mode；只新增种子数据，不修改已应用的 V23，避免 Flyway checksum 失败。
-- 占位符仅使用白名单：{{candidateName}} {{jobTitle}} {{companyName}} {{topSkill}} {{location}} {{email}} {{phone}}
INSERT INTO communication_template
    (user_id, scene, template_type, output_language, name, description, body_text, is_system)
VALUES
(NULL, 'FOLLOW_UP', 'OPENING_MESSAGE', 'EN', 'Follow-up Message - Brief Inquiry',
 'A short, polite private-message follow-up after applying',
 'Hello, I am {{candidateName}}, with a background in {{topSkill}}. I applied for the {{jobTitle}} role at {{companyName}} and wanted to confirm you received my materials. If convenient, please let me know about next steps ({{email}} / {{phone}}).', 1),
(NULL, 'SALARY', 'OPENING_MESSAGE', 'EN', 'Salary Inquiry - Opening Message',
 'A private-message inquiry about the compensation range during the conversation',
 'Hello, I am {{candidateName}}. I am glad to continue the conversation with {{companyName}} about the {{jobTitle}} role. I would like to understand the salary and benefits range for this position so I can make a complete assessment. Contact: {{email}} / {{phone}}.', 1);
