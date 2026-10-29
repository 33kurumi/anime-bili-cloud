// ═══════════════════════════════════════════════════════════════
// MongoDB Init — 弹幕库初始化（改造后：animeKey + episodeNum + position 时间窗）
// 执行：docker exec -i <mongo> mongosh --username abc --password abc123 --authenticationDatabase abc_danmaku < mongo-init.js
// ═══════════════════════════════════════════════════════════════

db = db.getSiblingDB('abc_danmaku');

// 业务账号（首次部署才需要，重复执行会报错可忽略）
try {
  db.createUser({
    user: 'abc',
    pwd: 'abc123',
    roles: [{ role: 'readWrite', db: 'abc_danmaku' }]
  });
} catch (e) { print('user exists, skip'); }

db.createCollection('danmaku');

// 1) 主查询：按番剧+集号+时间窗（弹幕窗口 API，配合 Redis ZSET 兜底）
db.danmaku.createIndex({ animeKey: 1, episodeNum: 1, position: 1 }, { name: 'idx_window' });

// 2) 兼容旧字段（animeId/episodeId/timestamp）
db.danmaku.createIndex({ animeId: 1, episodeId: 1, timestamp: 1 }, { name: 'idx_legacy' });

// 3) 去重：同一集同一秒同一归一化文本的唯一性检查（写入前查，重复则 count+1）
db.danmaku.createIndex({ animeKey: 1, episodeNum: 1, normText: 1, position: 1 }, { name: 'idx_dedup' });

// 4) 用户维度：我的弹幕 / 风控回溯
db.danmaku.createIndex({ userId: 1, sendTime: -1 }, { name: 'idx_user' });

// 5) 管理端：被屏蔽弹幕检索
db.danmaku.createIndex({ blocked: 1, sendTime: -1 }, { name: 'idx_blocked' });

// 6) 冷数据清理（可选，按需开启）：180 天前的弹幕自动过期
// db.danmaku.createIndex({ sendTime: 1 }, { expireAfterSeconds: 15552000, name: 'ttl_180d' });

// 7) 弹幕内容审核/统计视图
db.danmaku.aggregate([
  { $group: { _id: { animeKey: '$animeKey', episodeNum: '$episodeNum' }, total: { $sum: 1 } } },
  { $out: 'danmaku_stats' }
]);

print('mongo init done: collections=' + db.getCollectionNames().join(','));
