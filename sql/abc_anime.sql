-- ═══════════════════════════════════════════════════════════════
-- Anime Bilibili Cloud — MySQL Schema
-- Database: abc_anime
-- ═══════════════════════════════════════════════════════════════

USE abc_anime;

-- ── User ──────────────────────────────────────────────────────
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(255) NOT NULL COMMENT '密码(BCrypt)',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar`      VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `bio`         VARCHAR(500) DEFAULT NULL COMMENT '个人简介',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/ADMIN',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0=禁用, 1=正常',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ── Anime ─────────────────────────────────────────────────────
DROP TABLE IF EXISTS `anime`;
CREATE TABLE `anime` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '番剧ID',
    `title`          VARCHAR(200) NOT NULL COMMENT '番剧名称',
    `cover_url`      VARCHAR(500) DEFAULT NULL COMMENT '封面图URL',
    `description`    TEXT         DEFAULT NULL COMMENT '剧情简介',
    `type`           VARCHAR(20)  NOT NULL DEFAULT 'TV' COMMENT '类型: TV/MOVIE/OVA/ONA/SPECIAL',
    `status`         VARCHAR(20)  NOT NULL DEFAULT '连载中' COMMENT '状态: 连载中/已完结/未开播',
    `year`           INT          DEFAULT NULL COMMENT '播出年份',
    `season`         VARCHAR(10)  DEFAULT NULL COMMENT '播出季度: 1月/4月/7月/10月',
    `episodes_count` INT          DEFAULT 0 COMMENT '总集数',
    `rating_avg`     DOUBLE       DEFAULT 0.0 COMMENT '平均评分',
    `view_count`     BIGINT       DEFAULT 0 COMMENT '总播放量',
    `bangumi_id`     VARCHAR(50)  DEFAULT NULL COMMENT 'Bangumi ID',
    `studio`         VARCHAR(100) DEFAULT NULL COMMENT '动画制作公司',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_type` (`type`),
    KEY `idx_year_season` (`year`, `season`),
    KEY `idx_rating` (`rating_avg`),
    FULLTEXT KEY `ft_title_desc` (`title`, `description`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='番剧表';

-- ── Episode ───────────────────────────────────────────────────
DROP TABLE IF EXISTS `episode`;
CREATE TABLE `episode` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '剧集ID',
    `anime_id`    BIGINT       NOT NULL COMMENT '番剧ID',
    `title`       VARCHAR(200) NOT NULL COMMENT '单集标题',
    `episode_num` INT          NOT NULL COMMENT '第几集',
    `video_url`   VARCHAR(500) DEFAULT NULL COMMENT '视频播放地址',
    `duration`    BIGINT       DEFAULT 0 COMMENT '时长(秒)',
    `play_count`  BIGINT       DEFAULT 0 COMMENT '播放量',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_anime_id` (`anime_id`),
    KEY `idx_episode_num` (`anime_id`, `episode_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧集表';

-- ── Tag ───────────────────────────────────────────────────────
DROP TABLE IF EXISTS `tag`;
CREATE TABLE `tag` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '标签ID',
    `name`       VARCHAR(50) NOT NULL COMMENT '标签名',
    `type`       VARCHAR(20) NOT NULL DEFAULT 'genre' COMMENT '类型: category/genre',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_type` (`name`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

-- ── Anime-Tag (M:N) ───────────────────────────────────────────
DROP TABLE IF EXISTS `anime_tag`;
CREATE TABLE `anime_tag` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `anime_id`   BIGINT   NOT NULL,
    `tag_id`     BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_anime_tag` (`anime_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='番剧-标签关联表';

-- ── Comment ───────────────────────────────────────────────────
DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `user_id`          BIGINT        NOT NULL COMMENT '用户ID',
    `username`         VARCHAR(50)   NOT NULL COMMENT '用户名(冗余)',
    `anime_id`         BIGINT        NOT NULL COMMENT '番剧ID',
    `episode_id`       BIGINT        DEFAULT NULL COMMENT '剧集ID(番剧级评论为NULL)',
    `content`          VARCHAR(2000) NOT NULL COMMENT '评论内容',
    `parent_id`        BIGINT        DEFAULT NULL COMMENT '父评论ID(楼中楼)',
    `reply_to_user_id` BIGINT        DEFAULT NULL COMMENT '回复目标用户ID',
    `reply_to_username` VARCHAR(50)  DEFAULT NULL COMMENT '回复目标用户名',
    `like_count`       INT           DEFAULT 0 COMMENT '点赞数',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_anime_id` (`anime_id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';

-- ── Rating ────────────────────────────────────────────────────
DROP TABLE IF EXISTS `rating`;
CREATE TABLE `rating` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL COMMENT '用户ID',
    `anime_id`   BIGINT   NOT NULL COMMENT '番剧ID',
    `score`      TINYINT  NOT NULL COMMENT '评分(1-10)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_anime` (`user_id`, `anime_id`),
    KEY `idx_anime_id` (`anime_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评分表';

-- ── User Follow ───────────────────────────────────────────────
DROP TABLE IF EXISTS `user_follow`;
CREATE TABLE `user_follow` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `follower_id` BIGINT   NOT NULL COMMENT '关注者ID',
    `followee_id` BIGINT   NOT NULL COMMENT '被关注者ID',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee_id` (`followee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注表';

-- ── Watch History ─────────────────────────────────────────────
DROP TABLE IF EXISTS `watch_history`;
CREATE TABLE `watch_history` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL COMMENT '用户ID',
    `episode_id` BIGINT   NOT NULL COMMENT '剧集ID',
    `anime_id`   BIGINT   NOT NULL COMMENT '番剧ID',
    `progress`   BIGINT   DEFAULT 0 COMMENT '观看进度(秒)',
    `watched_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观看时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_episode` (`user_id`, `episode_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观看历史表';

-- ── Video ─────────────────────────────────────────────────────
DROP TABLE IF EXISTS `video`;
CREATE TABLE `video` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `episode_id` BIGINT       NOT NULL COMMENT '剧集ID',
    `video_url`  VARCHAR(500) NOT NULL COMMENT '视频地址',
    `file_size`  BIGINT       DEFAULT 0 COMMENT '文件大小(bytes)',
    `quality`    VARCHAR(10)  DEFAULT '720P' COMMENT '清晰度',
    `duration`   BIGINT       DEFAULT 0 COMMENT '时长(秒)',
    `format`     VARCHAR(10)  DEFAULT 'mp4' COMMENT '格式',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_episode_id` (`episode_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频资源表';

-- ═══════════════════════════════════════════════════════════════
-- Seed Data — Sample tags
-- ═══════════════════════════════════════════════════════════════
INSERT INTO `tag` (`name`, `type`) VALUES
('热血', 'genre'), ('搞笑', 'genre'), ('恋爱', 'genre'),
('异世界', 'genre'), ('治愈', 'genre'), ('悬疑', 'genre'),
('科幻', 'genre'), ('奇幻', 'genre'), ('战斗', 'genre'),
('日常', 'genre'), ('美食', 'genre'), ('音乐', 'genre'),
('运动', 'genre'), ('校园', 'genre'), ('冒险', 'genre'),
('TV动画', 'category'), ('剧场版', 'category'), ('OVA', 'category');

-- Sample user (password: admin123)
INSERT INTO `user` (`username`, `password`, `email`, `role`, `status`) VALUES
('admin', '$2a$10$H3CVgakeabyQL1DFItLG.ewOsPbmVB.b/WrEU0ZIj5jLcYWCSK9r6', 'admin@abc.com', 'ADMIN', 1);
