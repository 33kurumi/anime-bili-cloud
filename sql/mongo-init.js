-- ═══════════════════════════════════════════════════════════════
-- MongoDB Init — Create danmaku database and user
-- ═══════════════════════════════════════════════════════════════

db = db.getSiblingDB('abc_danmaku');

// Create user for danmaku database
db.createUser({
  user: 'abc',
  pwd: 'abc123',
  roles: [
    { role: 'readWrite', db: 'abc_danmaku' }
  ]
});

// Create danmaku collection with indexes
db.createCollection('danmaku');

// Index for time-range queries (fetch danmaku for a specific episode)
db.danmaku.createIndex({ animeId: 1, episodeId: 1, timestamp: 1 });

// TTL index: auto-delete danmaku older than 180 days (optional cleanup)
// db.danmaku.createIndex({ sendTime: 1 }, { expireAfterSeconds: 15552000 });
