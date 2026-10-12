-- ═══════════════════════════════════════════════════════════════
-- AI 助手：会话与消息（对话记忆）
-- ═══════════════════════════════════════════════════════════════
USE abc_anime;

CREATE TABLE IF NOT EXISTS `ai_chat_session` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `title`      VARCHAR(100) DEFAULT '新对话' COMMENT '会话标题',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话';

CREATE TABLE IF NOT EXISTS `ai_chat_message` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT      NOT NULL COMMENT '会话ID',
    `role`       VARCHAR(16) NOT NULL COMMENT 'system/user/assistant',
    `content`    TEXT        NOT NULL COMMENT '消息内容',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息';
