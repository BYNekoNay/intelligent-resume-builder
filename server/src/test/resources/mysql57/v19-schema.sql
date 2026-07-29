SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_consent` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `policy_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_scopes_json` json NOT NULL,
  `data_categories_json` json NOT NULL,
  `notice_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ai_consent_user` (`user_id`),
  CONSTRAINT `fk_ai_consent_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `task_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `idempotency_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_fingerprint` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `input_snapshot_json` json NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `result_json` json DEFAULT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirmation_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `result_resume_version_id` bigint(20) DEFAULT NULL,
  `retry_count` int(11) NOT NULL DEFAULT '0',
  `lease_owner` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_expires_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `parent_task_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_task_idem` (`user_id`,`task_type`,`idempotency_key`),
  KEY `idx_ai_task_status` (`status`,`lease_expires_at`),
  KEY `idx_ai_task_user` (`user_id`),
  KEY `fk_ai_task_result_version` (`result_resume_version_id`),
  KEY `idx_ai_task_parent` (`parent_task_id`),
  CONSTRAINT `fk_ai_task_parent` FOREIGN KEY (`parent_task_id`) REFERENCES `ai_task` (`id`),
  CONSTRAINT `fk_ai_task_result_version` FOREIGN KEY (`result_resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_ai_task_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `application_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `job_description_id` bigint(20) NOT NULL,
  `resume_version_id` bigint(20) NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cover_letter_text` text COLLATE utf8mb4_unicode_ci,
  `email_body_text` text COLLATE utf8mb4_unicode_ci,
  `opening_message_text` text COLLATE utf8mb4_unicode_ci,
  `feedback_text` text COLLATE utf8mb4_unicode_ci,
  `applied_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_application_user_updated` (`user_id`,`updated_at`),
  KEY `fk_application_job` (`job_description_id`),
  KEY `fk_application_resume_version` (`resume_version_id`),
  CONSTRAINT `fk_application_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_application_resume_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_application_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ats_check_result` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `resume_version_id` bigint(20) NOT NULL,
  `job_description_id` bigint(20) NOT NULL,
  `total_score` decimal(5,2) NOT NULL,
  `result_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ats_check_user_created` (`user_id`,`created_at`),
  KEY `fk_ats_check_resume_version` (`resume_version_id`),
  KEY `fk_ats_check_job` (`job_description_id`),
  CONSTRAINT `fk_ats_check_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_ats_check_resume_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_ats_check_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `auth_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `token_family_id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `refresh_token_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `issued_at` datetime(3) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `revoke_reason` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_agent` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ip_address` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_auth_session_user` (`user_id`),
  KEY `idx_auth_session_family` (`token_family_id`),
  CONSTRAINT `fk_auth_session_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `career_material` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `material_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_json` json NOT NULL,
  `source_text` mediumtext COLLATE utf8mb4_unicode_ci,
  `usage_preference` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_career_material_user` (`user_id`),
  KEY `idx_career_material_user_type` (`user_id`,`material_type`),
  CONSTRAINT `fk_career_material_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `communication_draft` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `resume_version_id` bigint(20) NOT NULL,
  `job_description_id` bigint(20) NOT NULL,
  `draft_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `draft_text` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_communication_user_created` (`user_id`,`created_at`),
  KEY `fk_communication_resume_version` (`resume_version_id`),
  KEY `fk_communication_job` (`job_description_id`),
  CONSTRAINT `fk_communication_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_communication_resume_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_communication_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `export_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `resume_version_id` bigint(20) NOT NULL,
  `template_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `storage_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_size_bytes` bigint(20) DEFAULT NULL,
  `sha256` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `retry_count` int(11) NOT NULL DEFAULT '0',
  `expires_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `lease_owner` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_expires_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_export_task_user` (`user_id`),
  KEY `idx_export_task_status` (`status`),
  KEY `fk_export_task_version` (`resume_version_id`),
  KEY `idx_export_task_claim` (`status`,`lease_expires_at`,`id`),
  CONSTRAINT `fk_export_task_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
  CONSTRAINT `fk_export_task_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inline_optimization_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `resume_version_id` bigint(20) NOT NULL,
  `job_description_id` bigint(20) DEFAULT NULL,
  `section_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `original_content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_json` json NOT NULL,
  `provider_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_inline_optimization_user_created` (`user_id`,`created_at`),
  KEY `idx_inline_optimization_resume_version` (`resume_version_id`),
  KEY `fk_inline_optimization_job` (`job_description_id`),
  CONSTRAINT `fk_inline_optimization_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_inline_optimization_resume_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_inline_optimization_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `interview_answer_asset` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `interview_record_id` bigint(20) DEFAULT NULL,
  `question_text` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `original_answer_text` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `suggested_answer_text` text COLLATE utf8mb4_unicode_ci,
  `feedback_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_interview_asset_user_created` (`user_id`,`created_at`),
  CONSTRAINT `fk_interview_asset_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `interview_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `session_id` bigint(20) NOT NULL,
  `question_text` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `answer_text` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `round_score` int(11) NOT NULL,
  `feedback_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `round_no` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_interview_record_session_round` (`session_id`,`round_no`),
  KEY `idx_interview_record_session_created` (`session_id`,`created_at`),
  CONSTRAINT `fk_interview_record_session` FOREIGN KEY (`session_id`) REFERENCES `interview_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `interview_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `source_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resume_version_id` bigint(20) DEFAULT NULL,
  `external_resume_text` mediumtext COLLATE utf8mb4_unicode_ci,
  `job_description_id` bigint(20) DEFAULT NULL,
  `interview_mode` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `current_question` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_interview_session_user_updated` (`user_id`,`updated_at`),
  KEY `fk_interview_session_resume` (`resume_version_id`),
  KEY `fk_interview_session_job` (`job_description_id`),
  CONSTRAINT `fk_interview_session_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_interview_session_resume` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_interview_session_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `job_description` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `company_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `jd_text` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `parsed_keywords_json` json DEFAULT NULL,
  `parsed_at` datetime(3) DEFAULT NULL,
  `parsed_version` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_job_description_user` (`user_id`),
  CONSTRAINT `fk_job_description_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `match_result` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `resume_version_id` bigint(20) NOT NULL,
  `job_description_id` bigint(20) NOT NULL,
  `total_score` decimal(5,2) NOT NULL,
  `keyword_score` decimal(5,2) NOT NULL,
  `skill_score` decimal(5,2) NOT NULL,
  `experience_score` decimal(5,2) NOT NULL,
  `explanation_json` json NOT NULL,
  `rule_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_match_result_version` (`resume_version_id`),
  KEY `idx_match_result_jd` (`job_description_id`),
  CONSTRAINT `fk_match_result_jd` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_match_result_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `material_resume_generation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `raw_material_text` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `job_description_id` bigint(20) DEFAULT NULL,
  `generated_resume_json` json NOT NULL,
  `suggestions_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_material_generation_user_created` (`user_id`,`created_at`),
  KEY `fk_material_generation_job` (`job_description_id`),
  CONSTRAINT `fk_material_generation_job` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_material_generation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `personal_profile` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `full_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `location` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `website` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `profile_summary` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `target_role_titles` json DEFAULT NULL,
  `target_seniority` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_industries` json DEFAULT NULL,
  `target_work_preferences` json DEFAULT NULL,
  `career_positioning_summary` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_personal_profile_user` (`user_id`),
  CONSTRAINT `fk_personal_profile_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `resume` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `current_version_id` bigint(20) DEFAULT NULL,
  `job_description_id` bigint(20) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_resume_user` (`user_id`),
  KEY `fk_resume_current_version` (`current_version_id`),
  KEY `fk_resume_job_description` (`job_description_id`),
  KEY `idx_resume_user_jd` (`user_id`,`job_description_id`),
  CONSTRAINT `fk_resume_current_version` FOREIGN KEY (`current_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_resume_job_description` FOREIGN KEY (`job_description_id`) REFERENCES `job_description` (`id`),
  CONSTRAINT `fk_resume_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `resume_material_reference` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `resume_version_id` bigint(20) NOT NULL,
  `material_id` bigint(20) NOT NULL,
  `selection_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `output_path` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_snapshot_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `selection_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_rmr_version` (`resume_version_id`),
  KEY `idx_rmr_material` (`material_id`),
  CONSTRAINT `fk_rmr_material` FOREIGN KEY (`material_id`) REFERENCES `career_material` (`id`),
  CONSTRAINT `fk_rmr_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `resume_version` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `resume_id` bigint(20) NOT NULL,
  `version_no` int(11) NOT NULL,
  `source_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resume_json` json NOT NULL,
  `optimization_summary` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `generation_context` json DEFAULT NULL,
  `created_by` bigint(20) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `deleted_at` datetime(3) DEFAULT NULL,
  `restored_from_version_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resume_version_no` (`resume_id`,`version_no`),
  KEY `idx_resume_version_resume` (`resume_id`),
  KEY `fk_resume_version_user` (`created_by`),
  KEY `idx_resume_version_restored_from` (`restored_from_version_id`),
  CONSTRAINT `fk_resume_version_restored_from` FOREIGN KEY (`restored_from_version_id`) REFERENCES `resume_version` (`id`),
  CONSTRAINT `fk_resume_version_resume` FOREIGN KEY (`resume_id`) REFERENCES `resume` (`id`),
  CONSTRAINT `fk_resume_version_user` FOREIGN KEY (`created_by`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS;
