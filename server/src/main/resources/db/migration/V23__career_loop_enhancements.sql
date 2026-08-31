-- V23: 求职闭环 4 功能增强（F1/F2/F3）
-- 兼容 MySQL 5.7 / H2 MySQL mode（单条 ALTER 只改一列）

-- 1) F3: application_record 新增 next_follow_up_at（可空）
ALTER TABLE application_record ADD COLUMN next_follow_up_at DATETIME(3) NULL;

-- 2) F2: communication_template 沟通模板表
CREATE TABLE communication_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    scene VARCHAR(32) NOT NULL,
    template_type VARCHAR(32) NOT NULL,
    output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN',
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    body_text TEXT NOT NULL,
    is_system TINYINT(1) NOT NULL DEFAULT 0,
    usage_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_comm_tpl_scene_lang (scene, output_language, is_system),
    INDEX idx_comm_tpl_user (user_id),
    CONSTRAINT fk_comm_tpl_user FOREIGN KEY (user_id) REFERENCES user(id)
);

-- 3) F1: interview_asset_section 资产<->简历章节/素材关联
CREATE TABLE interview_asset_section (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    section_key VARCHAR(32) NOT NULL,
    material_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_asset_section_material (asset_id, section_key, material_id),
    INDEX idx_asset_section_user_key (user_id, section_key),
    CONSTRAINT fk_asset_section_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_asset_section_asset FOREIGN KEY (asset_id) REFERENCES interview_answer_asset(id),
    CONSTRAINT fk_asset_section_material FOREIGN KEY (material_id) REFERENCES career_material(id)
);

-- 4) F2: 内置模板种子数据（只读，user_id=NULL, is_system=1），共 14 行
-- 占位符仅使用白名单：{{candidateName}} {{jobTitle}} {{companyName}} {{topSkill}} {{location}} {{email}} {{phone}}
INSERT INTO communication_template
    (user_id, scene, template_type, output_language, name, description, body_text, is_system)
VALUES
(NULL, 'FOLLOW_UP', 'EMAIL', 'ZH_CN', '跟进邮件-温和提醒',
 '投递 3-5 个工作日未回复时的温和跟进',
 '主题：关于 {{jobTitle}} 岗位的跟进\n\n您好，{{companyName}} 招聘团队：\n\n我是 {{candidateName}}，{{topSkill}} 相关背景。此前就 {{jobTitle}} 岗位提交了申请，想确认材料是否收到，也希望补充说明我的匹配点。\n\n如需更多信息，欢迎随时联系（{{email}} / {{phone}}）。\n\n祝好，\n{{candidateName}}', 1),
(NULL, 'FOLLOW_UP', 'EMAIL', 'EN', 'Follow-up Email - Gentle Reminder',
 'A gentle reminder when there is no reply after 3-5 business days',
 'Subject: Follow-up on {{jobTitle}} position\n\nDear {{companyName}} recruiting team,\n\nI am {{candidateName}}, with a background in {{topSkill}}. I applied for the {{jobTitle}} position earlier and wanted to confirm you received my materials. I would also welcome the chance to elaborate on my fit.\n\nFeel free to reach me at {{email}} / {{phone}}.\n\nBest regards,\n{{candidateName}}', 1),
(NULL, 'FOLLOW_UP', 'OPENING_MESSAGE', 'ZH_CN', '跟进私信-简洁询问',
 '投递后私信跟进，简短礼貌',
 '您好，我是 {{candidateName}}，{{topSkill}} 相关背景。此前申请了 {{companyName}} 的 {{jobTitle}} 岗位，想确认材料是否收到。如方便，欢迎回复告知后续安排（{{email}} / {{phone}}）。', 1),
(NULL, 'THANK_YOU', 'EMAIL', 'ZH_CN', '面试感谢信',
 '面试后 24 小时内发送的感谢信',
 '主题：感谢 {{jobTitle}} 岗位面试\n\n您好，{{companyName}} 的 {{jobTitle}} 面试官：\n\n感谢您今天抽出时间面试。我对 {{jobTitle}} 岗位与团队方向很感兴趣，也进一步确认了 {{topSkill}} 相关经验与岗位的匹配。\n\n如有补充材料需要，请联系我（{{email}} / {{phone}}）。\n\n此致\n{{candidateName}}', 1),
(NULL, 'THANK_YOU', 'EMAIL', 'EN', 'Interview Thank-you Email',
 'A thank-you note sent within 24 hours after an interview',
 'Subject: Thank you for the {{jobTitle}} interview\n\nDear {{companyName}} interviewers,\n\nThank you for taking the time to interview me today. I am very interested in the {{jobTitle}} role and the team direction, and the conversation confirmed how my {{topSkill}} experience aligns with the position.\n\nPlease feel free to reach me at {{email}} / {{phone}} if you need anything else.\n\nBest regards,\n{{candidateName}}', 1),
(NULL, 'SALARY', 'EMAIL', 'ZH_CN', '薪资沟通-礼貌询问',
 '收到 offer 后礼貌询问薪资范围',
 '主题：关于 {{jobTitle}} 岗位的薪资沟通\n\n您好，{{companyName}} 的招聘团队：\n\n感谢 offer 沟通。{{jobTitle}} 岗位的职责与我 {{topSkill}} 背景高度匹配，我很期待加入。\n\n想进一步了解该岗位的薪资与福利范围，以便我做出完整评估。我的联系方式：{{email}} / {{phone}}。\n\n期待您的回复。\n{{candidateName}}', 1),
(NULL, 'SALARY', 'EMAIL', 'EN', 'Salary Inquiry - Polite Request',
 'Politely ask about the compensation range after receiving an offer',
 'Subject: Compensation discussion for the {{jobTitle}} role\n\nDear {{companyName}} recruiting team,\n\nThank you for the offer conversation. The {{jobTitle}} responsibilities align closely with my {{topSkill}} background, and I am excited about joining.\n\nI would like to understand the salary and benefits range for this role so I can make a complete assessment. You can reach me at {{email}} / {{phone}}.\n\nLooking forward to your reply.\n{{candidateName}}', 1),
(NULL, 'SALARY', 'OPENING_MESSAGE', 'ZH_CN', '薪资咨询-开场消息',
 '沟通阶段私信咨询薪资范围',
 '您好，我是 {{candidateName}}。很高兴与 {{companyName}} 就 {{jobTitle}} 岗位进一步沟通，想了解该岗位的薪资与福利范围，方便我综合评估。联系方式：{{email}} / {{phone}}。', 1),
(NULL, 'DECLINE', 'EMAIL', 'ZH_CN', '婉拒 offer-诚恳致谢',
 '决定不接受的婉拒邮件',
 '主题：关于 {{jobTitle}} 岗位的决定\n\n您好，{{companyName}} 招聘团队：\n\n非常感谢贵司给予 {{jobTitle}} 岗位的 offer 与沟通中的诚意。经过慎重考虑，我最终选择接受另一份更契合当前职业规划的机会，因此无法接受这份 offer。\n\n再次感谢面试团队的认可，祝贵司招聘顺利。\n\n此致\n{{candidateName}}', 1),
(NULL, 'DECLINE', 'EMAIL', 'EN', 'Offer Decline - Sincere Thanks',
 'A sincere decline email when deciding not to accept an offer',
 'Subject: Decision regarding the {{jobTitle}} role\n\nDear {{companyName}} recruiting team,\n\nThank you very much for the {{jobTitle}} offer and the sincerity throughout the process. After careful consideration, I have decided to accept another opportunity that better fits my current career plan, so I am unable to accept this offer.\n\nThank you again for the recognition from the interview team. I wish your recruiting efforts every success.\n\nBest regards,\n{{candidateName}}', 1),
(NULL, 'GENERAL', 'COVER_LETTER', 'ZH_CN', '通用求职信',
 '面向 {{jobTitle}} 岗位的通用求职信',
 '您好，我是 {{candidateName}}，现居 {{location}}。我希望申请 {{companyName}} 的 {{jobTitle}} 岗位。我的 {{topSkill}} 相关经验与岗位要求高度匹配，简历中记录了可核实的经历与成果。\n\n联系方式：{{email}} / {{phone}}。期待进一步沟通。\n\n此致\n{{candidateName}}', 1),
(NULL, 'GENERAL', 'COVER_LETTER', 'EN', 'General Cover Letter',
 'A general cover letter for the {{jobTitle}} role',
 'Hello, I am {{candidateName}}, based in {{location}}. I would like to apply for the {{jobTitle}} role at {{companyName}}. My experience with {{topSkill}} aligns closely with the requirements, and my resume documents verifiable experience and results.\n\nYou can reach me at {{email}} / {{phone}}. I look forward to discussing the opportunity.\n\nBest regards,\n{{candidateName}}', 1),
(NULL, 'GENERAL', 'OPENING_MESSAGE', 'ZH_CN', '通用打招呼-开场消息',
 '向招聘方发送的通用开场消息',
 '您好，我是 {{candidateName}}，现居 {{location}}。我关注到 {{companyName}} 的 {{jobTitle}} 岗位，我的 {{topSkill}} 相关经验与岗位要求高度匹配，希望有机会进一步交流。联系方式：{{email}} / {{phone}}。', 1),
(NULL, 'GENERAL', 'OPENING_MESSAGE', 'EN', 'General Opening Message',
 'A general opening message to a recruiter',
 'Hello, I am {{candidateName}}, based in {{location}}. I came across the {{jobTitle}} role at {{companyName}}, and my experience with {{topSkill}} aligns closely with the requirements. I would welcome the chance to discuss it further. You can reach me at {{email}} / {{phone}}.', 1);
