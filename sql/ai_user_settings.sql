-- 用户级 AI / 代理设置（个人中心可改）
ALTER TABLE user
  ADD COLUMN ai_api_key   VARCHAR(200)  DEFAULT NULL COMMENT '用户自己的大模型 API Key（可空）',
  ADD COLUMN ai_persona   VARCHAR(1000) DEFAULT NULL COMMENT '用户自定义桌宠人设（可空）',
  ADD COLUMN ai_proxy_port INT          DEFAULT NULL COMMENT '用户本地代理端口（Bangumi 魔法兜底用）';
