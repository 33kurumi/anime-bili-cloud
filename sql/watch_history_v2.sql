-- 说明：本脚本属于 abc_anime 库的增量 DDL，必须连接到对应数据库执行。
USE abc_anime;

-- 观看历史扩展：支持远程番剧的统一标识（animeKey + episodeNum）
ALTER TABLE watch_history
  ADD COLUMN anime_key   VARCHAR(64)  DEFAULT NULL COMMENT '统一番剧标识 bgm:123/123' AFTER episode_id,
  ADD COLUMN episode_num INT          DEFAULT NULL COMMENT '集号' AFTER anime_key,
  ADD COLUMN title       VARCHAR(200) DEFAULT NULL COMMENT '番剧标题' AFTER episode_num,
  ADD COLUMN cover_url   VARCHAR(500) DEFAULT NULL COMMENT '封面' AFTER title;
-- 唯一约束：同一用户同一集只留一条（改进度用更新）
ALTER TABLE watch_history ADD UNIQUE KEY uk_user_anime_ep (user_id, anime_key, episode_num);
