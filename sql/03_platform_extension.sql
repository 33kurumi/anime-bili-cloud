-- =============================================================
-- 03_platform_extension.sql
-- 平台能力扩展表（P1 改造新增）
--   1. user_favorite   收藏 / 追番（双端同步、RAG 上下文来源）
--   2. search_history  搜索历史（RAG 上下文来源 + 搜索联想）
--   3. user_progress   追番进度同步（增量游标 lastSyncAt，参考 Animeko EpisodeCollectionSyncer）
--   4. danmaku_report  弹幕举报（管理端审核）
-- 执行顺序：先 abc_anime.sql / watch_history_v2.sql，再执行本文件。
-- 说明：本脚本属于 abc_anime 库的增量 DDL，必须连接到对应数据库执行。
USE abc_anime;
-- =============================================================

-- ---------- 1. 收藏 / 追番 ----------
CREATE TABLE IF NOT EXISTS user_favorite (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       BIGINT       NOT NULL COMMENT '用户ID',
  anime_key     VARCHAR(64)  NOT NULL COMMENT '番剧统一标识 bgm:123',
  bangumi_id    BIGINT       DEFAULT NULL COMMENT 'Bangumi 原始ID',
  title         VARCHAR(200) DEFAULT NULL COMMENT '番剧标题快照',
  cover_url     VARCHAR(500) DEFAULT NULL COMMENT '封面快照',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1想看 2在看 3看过 4弃番',
  is_follow     TINYINT      NOT NULL DEFAULT 1 COMMENT '1追番 0仅收藏',
  last_episode  INT          NOT NULL DEFAULT 0 COMMENT '最近看到第几集',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_anime (user_id, anime_key),
  KEY idx_user_status (user_id, status),
  KEY idx_user_update (user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏/追番';

-- ---------- 2. 搜索历史 ----------
CREATE TABLE IF NOT EXISTS search_history (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  user_id      BIGINT       NOT NULL COMMENT '用户ID（0 表示匿名）',
  keyword      VARCHAR(128) NOT NULL COMMENT '搜索词（原始）',
  norm_keyword VARCHAR(128) NOT NULL COMMENT '归一化搜索词（全角转半角/去标点/小写）',
  result_count INT          NOT NULL DEFAULT 0 COMMENT '命中数量',
  source       VARCHAR(16)  NOT NULL DEFAULT 'WEB' COMMENT 'WEB/APP/AI',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_norm (norm_keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搜索历史';

-- ---------- 3. 追番进度同步（跨端增量同步） ----------
CREATE TABLE IF NOT EXISTS user_progress (
  id             BIGINT   NOT NULL AUTO_INCREMENT,
  user_id        BIGINT   NOT NULL,
  anime_key      VARCHAR(64) NOT NULL,
  episode_num    INT      NOT NULL DEFAULT 1,
  position_sec   INT      NOT NULL DEFAULT 0 COMMENT '播放到第几秒',
  duration_sec   INT      NOT NULL DEFAULT 0 COMMENT '该集总时长',
  finished       TINYINT  NOT NULL DEFAULT 0 COMMENT '1 已看完',
  device         VARCHAR(32) DEFAULT 'web' COMMENT '上报设备',
  update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_anime_ep (user_id, anime_key, episode_num),
  KEY idx_user_time (user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追番进度（增量同步游标 lastSyncAt=update_time）';

-- ---------- 4. 弹幕举报 ----------
CREATE TABLE IF NOT EXISTS danmaku_report (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  danmaku_id  VARCHAR(64) NOT NULL COMMENT 'Mongo 弹幕 _id',
  anime_key   VARCHAR(64) NOT NULL,
  episode_num INT      NOT NULL,
  user_id     BIGINT   NOT NULL COMMENT '举报人',
  reason      VARCHAR(64) NOT NULL DEFAULT 'OTHER' COMMENT 'SPAM/ABUSE/SPOILER/OTHER',
  detail      VARCHAR(500) DEFAULT NULL,
  status      TINYINT  NOT NULL DEFAULT 0 COMMENT '0待处理 1已屏蔽 2驳回',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status (status, create_time),
  KEY idx_danmaku (danmaku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='弹幕举报';

-- ---------- 5. AI 会话增强 ----------
-- 注意：ai_chat.sql 中 ai_chat_session 已有 title / created_at / updated_at 列，
-- 这里只新增 rag_snapshot（RAG 上下文快照），不要重复 ADD COLUMN title。
ALTER TABLE ai_chat_session
  ADD COLUMN rag_snapshot TEXT DEFAULT NULL COMMENT '最近一次 RAG 上下文快照（JSON）' AFTER title;

-- ---------- 种子数据（演示用） ----------
INSERT IGNORE INTO user_favorite (user_id, anime_key, bangumi_id, title, cover_url, status, is_follow, last_episode)
VALUES (1, 'bgm:400602', 400602, '孤独摇滚！', 'https://lain.bgm.tv/pic/cover/l/6f/8b/400602_x0Q7y.jpg', 2, 1, 3),
       (1, 'bgm:326895', 326895, '间谍过家家', 'https://lain.bgm.tv/pic/cover/l/2b/9a/326895_1XkO6.jpg', 2, 1, 1);

INSERT IGNORE INTO search_history (user_id, keyword, norm_keyword, result_count, source)
VALUES (1, '孤独摇滚', '孤独摇滚', 12, 'WEB'),
       (1, 'SPY×FAMILY', 'spyfamily', 8, 'WEB');
