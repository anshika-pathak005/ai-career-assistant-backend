-- Flyway migration V1: Create initial CareerPilot schema
-- Baseline migration for existing production database

-- Users table
CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `current_role` varchar(255) DEFAULT NULL,
  `dream_job` varchar(255) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `experience_years` int DEFAULT NULL,
  `industries` json DEFAULT NULL,
  `is_profile_complete` bit(1) NOT NULL,
  `name` varchar(255) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `skills` json DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `wizard_step` int NOT NULL,
  `user_category` varchar(50) DEFAULT NULL,
  `goal_type` varchar(50) DEFAULT NULL,
  `project_level` varchar(50) DEFAULT NULL,
  `work_preference` varchar(50) DEFAULT NULL,
  `learning_style` varchar(255) DEFAULT NULL,
  `skill_levels` json DEFAULT NULL,
  `weekly_hours` int DEFAULT NULL,
  `current_month_year` varchar(255) NOT NULL,
  `plan` varchar(255) NOT NULL,
  `roadmaps_generated_this_month` int NOT NULL,
  `resumes_analyzed_this_month` int NOT NULL,
  `user_role` varchar(20) NOT NULL DEFAULT 'ROLE_USER',
  `token_version` int NOT NULL DEFAULT '0',
  `deletion_requested_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Resumes table
CREATE TABLE IF NOT EXISTS `resumes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL,
  `file_path` varchar(255) NOT NULL,
  `file_size` bigint DEFAULT NULL,
  `file_type` varchar(255) DEFAULT NULL,
  `upload_time` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_resume_user_uploaded` (`user_id`,`upload_time`),
  CONSTRAINT `FK340nuaivxiy99hslr3sdydfvv` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Resume analysis table
CREATE TABLE IF NOT EXISTS `resume_analysis` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `analysis_date` datetime(6) NOT NULL,
  `ats_score` int DEFAULT NULL,
  `current_role` varchar(255) DEFAULT NULL,
  `dream_job_match_summary` text,
  `email` varchar(255) DEFAULT NULL,
  `experience_level` varchar(255) DEFAULT NULL,
  `experience_years` double DEFAULT NULL,
  `formatting_score` int DEFAULT NULL,
  `full_name` varchar(255) DEFAULT NULL,
  `improvement_suggestions` json DEFAULT NULL,
  `interview_readiness` varchar(255) DEFAULT NULL,
  `missing_keywords` json DEFAULT NULL,
  `missing_skills` json DEFAULT NULL,
  `overall_score` int DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `project_quality_score` int DEFAULT NULL,
  `project_suggestions` json DEFAULT NULL,
  `raw_ai_response` text,
  `recommended_learning` json DEFAULT NULL,
  `strengths` json DEFAULT NULL,
  `summary` text,
  `technical_skills` json DEFAULT NULL,
  `technical_skills_score` int DEFAULT NULL,
  `weaknesses` json DEFAULT NULL,
  `resume_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `ats_suggestions` json DEFAULT NULL,
  `priority_skills` json DEFAULT NULL,
  `recommended_certifications` json DEFAULT NULL,
  `recommended_roles` json DEFAULT NULL,
  `recruiter_perspective` text,
  `resume_verdict` text,
  `section_analysis` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK2xrls1qmkylx6u63h7cu1x3og` (`resume_id`),
  KEY `idx_analysis_resume` (`resume_id`),
  KEY `idx_analysis_user` (`user_id`),
  CONSTRAINT `FK9ich95ljhgqxwix8fopsvhljs` FOREIGN KEY (`resume_id`) REFERENCES `resumes` (`id`),
  CONSTRAINT `FKmp7f8g8mwbu1x83g6tnurhn55` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Career roadmaps table
CREATE TABLE IF NOT EXISTS `career_roadmaps` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `archived_at` datetime(6) DEFAULT NULL,
  `dream_job_snapshot` varchar(255) NOT NULL,
  `generated_at` datetime(6) NOT NULL,
  `raw_ai_response` text,
  `status` enum('ACTIVE','ARCHIVED') NOT NULL,
  `summary` text,
  `total_estimated_weeks` int DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_roadmap_user_status` (`user_id`,`status`),
  KEY `idx_roadmap_user_generated` (`user_id`,`generated_at`),
  CONSTRAINT `FKg54ib4bl4r0y8hwtlis5ng4hr` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Roadmap phases table
CREATE TABLE IF NOT EXISTS `roadmap_phases` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) DEFAULT NULL,
  `estimated_weeks` int DEFAULT NULL,
  `phase_order` int DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `roadmap_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKnlhlu3mqycnbnnfgdhv1mev5a` (`roadmap_id`),
  CONSTRAINT `FKnlhlu3mqycnbnnfgdhv1mev5a` FOREIGN KEY (`roadmap_id`) REFERENCES `career_roadmaps` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Roadmap skills table
CREATE TABLE IF NOT EXISTS `roadmap_skills` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `estimated_days` int DEFAULT NULL,
  `is_existing_skill` bit(1) DEFAULT NULL,
  `resources` text,
  `skill_name` varchar(255) DEFAULT NULL,
  `skill_order` int DEFAULT NULL,
  `starting_level` varchar(255) DEFAULT NULL,
  `target_level` varchar(255) DEFAULT NULL,
  `phase_id` bigint NOT NULL,
  `project_description` text,
  `skill_type` varchar(255) DEFAULT NULL,
  `tech_stack` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKnhfck1f8yfc39c94quxb4yj40` (`phase_id`),
  CONSTRAINT `FKnhfck1f8yfc39c94quxb4yj40` FOREIGN KEY (`phase_id`) REFERENCES `roadmap_phases` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Skill steps table
CREATE TABLE IF NOT EXISTS `skill_steps` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `completed` bit(1) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `estimated_hours` int DEFAULT NULL,
  `step_order` int DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `skill_id` bigint NOT NULL,
  `skipped` bit(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9vkubnmbvfrh7mi08kupq2idy` (`skill_id`),
  CONSTRAINT `FK9vkubnmbvfrh7mi08kupq2idy` FOREIGN KEY (`skill_id`) REFERENCES `roadmap_skills` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Refresh tokens table
CREATE TABLE IF NOT EXISTS `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `device_info` varchar(255) DEFAULT NULL,
  `expiry_date` datetime(6) NOT NULL,
  `revoked` bit(1) NOT NULL,
  `token_hash` varchar(255) NOT NULL,
  `user_id` bigint NOT NULL,
  `family_id` varchar(36) DEFAULT NULL,
  `replaced_by_hash` varchar(255) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKo2mlirhldriil2y7krapq4frt` (`token_hash`),
  KEY `idx_refresh_family` (`family_id`),
  KEY `idx_refresh_user` (`user_id`),
  KEY `idx_refresh_expiry` (`expiry_date`),
  CONSTRAINT `FK1lih5y2npsf8u5o3vhdb9y0os` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;