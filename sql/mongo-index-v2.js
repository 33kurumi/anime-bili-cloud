// ═══════════════════════════════════════════════════════════════
// mongo-index-v2.js — 已部署库的索引升级脚本（不重建集合、不删数据）
// 执行：mongosh "mongodb://abc:abc123@localhost:27017/abc_danmaku?authSource=abc_danmaku" mongo-index-v2.js
// ═══════════════════════════════════════════════════════════════
db = db.getSiblingDB('abc_danmaku');

db.danmaku.createIndex({ animeKey: 1, episodeNum: 1, position: 1 }, { name: 'idx_window', background: true });
db.danmaku.createIndex({ animeKey: 1, episodeNum: 1, normText: 1, position: 1 }, { name: 'idx_dedup', background: true });
db.danmaku.createIndex({ userId: 1, sendTime: -1 }, { name: 'idx_user', background: true });
db.danmaku.createIndex({ blocked: 1, sendTime: -1 }, { name: 'idx_blocked', background: true });

// 老数据补齐 animeKey/episodeNum/normText 字段（幂等）
db.danmaku.find({ animeKey: { $exists: false } }).forEach(function (d) {
  db.danmaku.updateOne({ _id: d._id }, { $set: {
    animeKey: d.animeKey || (d.animeId ? 'bgm:' + d.animeId : String(d.animeId || '0')),
    episodeNum: d.episodeNum || d.episodeId || 1,
    position: d.position || d.timestamp || 0,
    normText: d.normText || (d.text || '').replace(/\s+/g, '')
  }});
});

print('index v2 done');
