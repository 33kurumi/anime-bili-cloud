# 🎬 Anime Bilibili Cloud (ABC)

> 毕业设计项目 —— 仿 B 站风格的番剧观看与弹幕互动平台，基于 **Spring Boot 3 + Spring Cloud 微服务架构**，集成大语言模型智能助手。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.1-blue)](https://spring.io/projects/spring-cloud)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3.x-42b883)](https://vuejs.org/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.0.0-purple)](https://docs.langchain4j.io/)

---

## 🆕 2026-10 P0/P1 改造摘要（对应答辩"技术工作量"）

本轮针对审计出的 P0 安全/真实性问题和 P1 技术深度做了集中改造，全部代码可编译、单测可离线运行（见文末"验证"）。

| 方向 | 改造内容 | 关键类 / 文件 |
|---|---|---|
| 网关安全 | 先剥离再注入 `X-User-Id`；只读接口匿名白名单；写操作/ADMIN 角色校验；Redis 黑名单校验；新增 `/ws/**` → `lb:ws://abc-danmaku-service` 路由 | `abc-gateway/.../filter/JwtAuthGlobalFilter.java` |
| 认证 | 验证码真校验（开关 `abc.auth.captcha-required`）；refresh 轮换并回传 role；logout 黑名单按剩余有效期 TTL；登录失败 10 次/15min 限流 | `abc-auth-service/.../AuthServiceImpl.java` |
| 弹幕高并发 | WS 握手 JWT 鉴权（`?token=` 或子协议）→ Redis List 缓冲 → 每秒批量 → RocketMQ 削峰（可开关）→ Consumer 批量落 MongoDB + 广播；ZSET 时间索引；屏蔽词/频率/5s 去重；dandanplay 三级匹配 | `abc-danmaku-service/**`（32 个源文件） |
| 弹幕新接口 | `GET /api/danmaku/window`（时间窗）、`/heat`（10s 分桶热度）、`/stats`（flush/命中率/在线） | `DanmakuController.java` |
| 视频源 | 五级排序（weight→成功率→响应时间→健康→随机）；`/api/source/play` 一次性返回直链+候选+签名代理；`/select/stream` SSE 分阶段选优；`/rule/test` 规则在线测试；代理 HMAC 签名 + SSRF 拦截 | `abc-source-service/.../select/SourceRanker.java` 等 |
| AI | RAG 注入观看历史 + 收藏 + 评分 + 搜索记录 + 番剧标签 + 候选；会话归属校验；写入 `rag_snapshot`；SSE 末尾 usage | `abc-ai-service/.../AiChatService.java` |
| 用户数据 | 新增收藏/追番、搜索历史、观看进度增量同步（`?since=`）、AI Key 掩码 | `abc-user-service/**` + `sql/03_platform_extension.sql` |
| 前端 | 匿名可浏览；`npm run build` 修复；播放页 hls.js + 自动换源（≤3）+ 10s 进度上报 + 跨端续播 + WS 指数退避重连；自研弹幕引擎（轨道分配/屏蔽过滤/设置面板）；统一 AppLayout；管理端 5 个 tab（含规则测试与热度 SVG） | `abc-frontend/src/**` |
| 主题 UI | 参考「青空次元」的信息层级：首页继续观看/追番优先、播放页两段式（播放区 + 选集/简介/评论）、深色主题与卡片化列表 | `AppLayout.vue` / `HomePage.vue` / `PlayPage.vue` |
| 部署与实验 | 每服务 Dockerfile + 20 服务 compose 编排 + Nacos 自动初始化；JMeter 弹幕/接口压测脚本；三份实验报告 + 答辩问答手册 | `README-DOCKER.md`、`jmeter/`、`docs/` |

---

## 📖 项目简介

Anime Bilibili Cloud（简称 **ABC**）是一个前后端分离的番剧（动漫）网站，采用 Spring Cloud 微服务架构。系统不直接存储影视内容，而是通过对接公开接口与**可配置的采集规则**完成番剧元数据聚合、视频直链解析与弹幕聚合，并在此基础上引入大语言模型"桌宠"助手，实现对话式番剧推荐与观剧交流。

四大核心能力：

- **📚 番剧元数据聚合**：对接 [Bangumi](https://bgm.tv/) 开放接口，获取封面、名称、评分、简介、分集、每日放送与热门评论，支持本地入库与多源聚合搜索（标签已落库到 `tag`/`anime_tag`，作为 AI 推荐上下文）。
- **🧩 规则化视频源引擎**：参考 [Animeko](https://github.com/open-ani/animeko) 与 [Kazumi](https://github.com/Predidit/Kazumi) 的规则思想，将各资源站的搜索/解析逻辑抽象为可配置规则（数据库存储、热更新），支持多源聚合、按可用性排序、故障切换与播放流代理。
- **💬 弹幕聚合与互动**：对接 [dandanplay（弹弹 play）](https://www.dandanplay.net/) 按集匹配弹幕并缓存至 MongoDB，同时支持登录用户通过 WebSocket 实时发送弹幕；发送链路为「WS 鉴权 → Redis 缓冲 → RocketMQ 削峰 → 批量落库 MongoDB → 广播」，两路弹幕合并展示。
- **🕹️ 播放体验**：服务端 `play` 一次性返回直链 + 备用候选 + 签名代理地址，前端 hls.js 播放失败自动换源；每 10s 上报进度，支持跨设备续播。
- **⭐ 追番与个性化**：收藏/追番、观看进度增量同步（`since` 游标）、搜索历史，全部作为 AI RAG 上下文。
- **🐾 AI 桌宠智能助手**：基于 LangChain4j 接入 DeepSeek 大模型，以页面内嵌"桌宠"为入口，结合用户观看历史、评分与番剧元数据（RAG 检索增强），提供 SSE 流式对话式推荐、番剧问答与多轮交流。

### 核心功能

| 模块 | 功能 |
|------|------|
| 🔐 账号相关 | 注册、登录(JWT)、退出、Token 刷新、验证码 |
| 🔍 找番 | 分类浏览、每日放送、热门排行、聚合搜索（本地 MySQL + Bangumi 实时；另有独立 Elasticsearch 检索服务，可切换） |
| 📺 看番 | 规则引擎多源解析直链、DPlayer 播放器、dandanplay 弹幕、WebSocket 实时弹幕 |
| 💬 互动 | 评分(1-10 分)、评论(楼中楼)、点赞、关注、观看历史 |
| 🐾 AI 助手 | 桌宠形象、流式对话、对话式推荐、番剧问答、多轮会话与偏好记忆；RAG 注入观看历史 + 收藏 + 评分 + 搜索记录 + 番剧标签 |
| ⭐ 追番 | 收藏/追番、观看进度增量同步、搜索历史 |
| 🛠️ 管理后台 | 番剧 CRUD、剧集管理、数据源管理、采集规则管理、规则在线测试（4 策略）、弹幕热度图 |

---

## 🏗️ 技术架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端: Vue 3 + Vite + TypeScript + Pinia + Element Plus       │
│        DPlayer + 桌宠组件(PetAssistant)            (:3000)    │
└────────────────────────────┬─────────────────────────────────┘
                             │ HTTP / WebSocket
┌────────────────────────────▼─────────────────────────────────┐
│  abc-gateway  (:8080)  Spring Cloud Gateway                   │
│  路由分发 / JWT 鉴权 / CORS / 限流                              │
└──┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬───────────┘
   ▼      ▼      ▼      ▼      ▼      ▼      ▼      ▼
┌────┐ ┌────┐ ┌─────┐ ┌────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌────┐
│Auth│ │User│ │Anime│ │Video│ │Dan- │ │Comm-│ │Sour-│ │ AI │
│8081│ │8082│ │8083 │ │8084│ │maku │ │ent  │ │ce   │ │8089│
│    │ │    │ │     │ │    │ │8085 │ │8086 │ │8088 │ │    │
└────┘ └────┘ └──┬──┘ └────┘ └──┬──┘ └─────┘ └──┬──┘ └────┘
                │              │              │
           ┌────▼────┐    ┌────▼────┐    ┌────▼─────┐
           │ Search  │    │ Bangumi │    │ 规则站点  │
           │  (ES)   │    │  开放API │    │ (多资源源)│
           │  :8087  │    └─────────┘    └──────────┘
           └─────────┘         dandanplay 弹幕 API
   ┌────────────────────────────────────────────────────────┐
   ▼        ▼        ▼          ▼            ▼          ▼
 MySQL8   Redis   MongoDB  Elasticsearch  RocketMQ✅   Nacos
 业务数据  缓存    弹幕存储    全文索引  削峰/广播/可关 注册/配置
```

> `abc-search-service`（:8087，Elasticsearch）已完整实现（建索引、MySQL→ES 全量/增量同步、多字段加权检索、联想、热搜），作为独立服务部署；网关当前默认搜索走 anime-service 的"本地 MySQL + Bangumi 实时聚合搜索"，可在网关按需将 `/api/search/**` 切换至 ES 服务（见路线图）。

### 微服务列表

| 服务 | 端口 | 主要技术 | 职责 |
|------|------|----------|------|
| **abc-gateway** | 8080 | Gateway + Nacos | API 网关、路由、JWT 鉴权、CORS |
| **abc-auth-service** | 8081 | MySQL + Redis + BCrypt | 注册登录、Token 签发/刷新 |
| **abc-user-service** | 8082 | MySQL + Redis | 用户资料、关注、观看历史 |
| **abc-anime-service** | 8083 | MySQL + Redis + Bangumi API | 番剧元数据、分集、每日放送、数据同步、聚合搜索 |
| **abc-video-service** | 8084 | MySQL + Redis | 播放信息、本地上传管理（备用） |
| **abc-danmaku-service** | 8085 | MongoDB + Redis + WebSocket | dandanplay 弹幕聚合、实时弹幕、在线人数 |
| **abc-comment-service** | 8086 | MySQL | 评论楼中楼、评分、点赞 |
| **abc-search-service** | 8087 | Elasticsearch + Redis | 全文检索、联想、热搜（独立部署，可切换） |
| **abc-source-service** | 8088 | MySQL + Jsoup | 采集规则引擎、多源搜索/直链解析、流代理 |
| **abc-ai-service** | 8089 | MySQL + LangChain4j + DeepSeek | AI 桌宠对话、会话记忆、推荐问答 |
| **abc-common** | — | Java Lib | 统一响应、异常、DTO、JWT 工具、注解 |

### 网关路由

| 路径前缀 | 转发目标 |
|----------|----------|
| `/api/auth/**` | abc-auth-service |
| `/api/user/**` | abc-user-service |
| `/api/anime/**`、`/api/datasource/**`、`/api/search/**` | abc-anime-service |
| `/api/video/**` | abc-video-service |
| `/api/danmaku/**` | abc-danmaku-service |
| `/api/comment/**`、`/api/rating/**` | abc-comment-service |
| `/api/source/**` | abc-source-service |
| `/api/ai/**` | abc-ai-service |
| `/ws/danmaku/**` | abc-danmaku-service（网关 WebSocket 路由，`lb:ws://`） |

---

## 🧩 核心设计

### 1. 规则化视频源引擎（abc-source-service）

- 规则以数据库表 `source_rule` 存储，字段包含资源站名称、搜索 URL、列表/选集/播放地址选择器（CSS/XPath）、请求头等，支持后台增删改查与**在线测试**（`POST /api/source/rule/test`，支持 `ruleId` 单规则与 `maccms` 直连两种模式）。
- 支持导入 Animeko / Kazumi 规则集（`/api/source/import/animeko`、`/import/kazumi`，对应 `sql/animeko_rules.sql`、`sql/kazumi_rules.sql`）。
- **播放接口**（一次请求拿全）：`GET /api/source/play/{animeKey}/{ep}` 返回 `url/format`（直链）、`headers`（Referer/UA）、`proxyUrl`（HMAC 签名的流代理地址）、`candidates`（备用源，附 `rankScore` 与 `rankReason`）、`stageUsed`（命中阶段）。
- **五级择优**：权重 → 成功率 → 响应时间 → 健康度 → 随机兜底，排序原因随响应返回，便于答辩演示"为什么选这个源"。
- **分阶段流式选优**：`GET /api/source/select/stream`（SSE）依次推送 `stage / candidate / final / complete` 事件，参考 Animeko 的 `MediaAutoSelector`：精确命中先返回，模糊匹配在超时预算内继续。
- `/api/source/proxy` 默认强制 HMAC 验签（`ts + sign`，300s 有效），并做 SSRF 拦截（回环/内网/云元数据/非法协议）。
- 新增资源源只需添加规则，无需改动业务代码。

### 2. 弹幕系统（abc-danmaku-service）

- **协议**：JSON over WebSocket，端点 `/ws/danmaku/{animeKey}/{episodeNum}`；登录用户握手时带 `?token=<JWT>`（或 `Sec-WebSocket-Protocol: bearer.<JWT>`），**游客只收不发**。
- **高并发写链路**：握手鉴权 → 限流/屏蔽词/去重 → Redis List 缓冲 → `DanmakuFlushTask` 每秒 drain → RocketMQ 削峰（`danmaku.mq.enabled`，默认直连）→ Consumer 批量落 MongoDB + 房间广播；Redis ZSET 维护时间索引（按集裁到 5 万条）。
- **来源**：① dandanplay 按番剧/集数匹配拉取（三级：`bangumiId` → 标题精确±1 集 → 标题模糊）；② 登录用户实时发送（`source=USER`）。
- **懒加载**：`GET /api/danmaku/list` 先查本地 MongoDB，为空则从 dandanplay 拉取并落库缓存，带归一化去重。
- **弹幕接口**：`/window`（按 `startTime/endTime` 取时间窗，播放端分片加载）、`/heat`（10s 分桶热度）、`/stats`（flush 次数/缓存命中率/在线数）。
- **前端渲染**：自研 Canvas 弹幕引擎（`components/danmaku/`）：轨道分配、滚动/顶部/底部三种位置、按颜色/关键词屏蔽、透明度/速度/字号设置面板。

```json
{
  "animeKey": "bgm:123", "episodeNum": 4,
  "userId": 789, "username": "用户",
  "text": "前方高能", "color": "#FFFFFF",
  "position": 0, "fontSize": 18,
  "timestamp": 12.5, "source": "USER"
}
```
> `animeKey` 为统一番剧标识（远程 `bgm:123` / 本地数字 id），`episodeNum` 为集号；`source` 由服务端标记。

### 3. AI 桌宠智能助手（abc-ai-service）

- 前端 `PetAssistant.vue`：可拖拽的 GIF 桌宠常驻页面，点击展开对话窗，支持空闲/拖拽动画与消息气泡。
- 后端基于 **LangChain4j 1.0.0 + langchain4j-open-ai** 接入 OpenAI 兼容接口（默认 DeepSeek `deepseek-chat`，可切换通义千问），通过 **SSE（Server-Sent Events）** 流式输出。
- 上下文（RAG 思路）：通过 OpenFeign 拉取用户观看历史（`/api/user/history`）与番剧检索结果（`/api/datasource/search`），组织为提示词上下文，使推荐基于用户真实数据而非凭空生成（后续将纳入评分、标签等更多特征）。
- 会话持久化：`ai_chat_session` / `ai_chat_message` 表保存多轮会话，支持新建会话、历史回溯与偏好记忆。
- **RAG 上下文**（`AiChatService.buildRagContext()`）：观看历史（最近 10 条）+ 收藏/追番 + 用户评分 + 搜索记录 + 番剧标签 + 检索候选，拼装后注入会话，快照写入 `ai_chat_session.rag_snapshot` 便于复现。
- **会话安全**：`GET /api/ai/messages?sessionId=` 校验会话归属，禁止越权读取他人对话。
- **降级链**：用户自带 Key → 全局 Key → 免费兜底模型；SSE 末尾追加 `usage` 事件便于统计。

### 4. 搜索引擎（abc-search-service）

- 索引 `abc_anime`：`title(text) / description(text) / tags(keyword) / year / season / type / rating_avg`。
- 多字段加权检索：`title³ + tags² + description¹`（AND 算子），标题前缀联想，Redis ZSet 统计热搜。
- 启动自检：ES 为空而 MySQL 有数据时自动全量重建；番剧同步后可经 Feign 触发增量/全量索引。

---

## 🛠️ 技术栈

### 后端

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 运行环境 |
| Spring Boot | 3.2.5 | 微服务基础框架 |
| Spring Cloud | 2023.0.1 | 微服务治理 |
| Spring Cloud Alibaba | 2023.0.1.0 | Nacos 集成 |
| Nacos | 2.3.1 | 注册中心 & 配置中心 |
| Spring Cloud Gateway | — | API 网关 |
| Spring Security Crypto | — | 密码加密（BCrypt，auth） |
| MyBatis-Plus | 3.5.6 | ORM 框架 |
| Druid | 1.2.22 | 数据库连接池 |
| MySQL | 8.0.33 | 关系型数据库 |
| Redis | 5.0.14 | 缓存 / 热搜 / 在线状态 |
| Redisson | 3.28.0 | Redis 客户端 |
| MongoDB | 5.0.5 | 弹幕存储 |
| Elasticsearch | 7.17.27 | 全文搜索引擎 |
| RocketMQ | 5.1.4 | 消息队列（弹幕写链路已接入：缓冲批量 → 削峰 → 批量落库，`danmaku.mq.enabled` 可开关降级为直连） |
| LangChain4j | 1.0.0 | 大模型应用编排（OpenAI 兼容） |
| Jsoup | 1.17.2 | 采集规则页面解析 |
| JWT (jjwt) | 0.12.5 | Token 认证 |
| Knife4j | 4.4.0 | API 接口文档 |
| Hutool | 5.8.27 | 工具类库 |
| Lombok | 1.18.32 | 简化代码 |

### 前端

| 技术 | 版本 | 说明 |
|------|------|------|
| Node.js | 22.14.0 | 运行环境 |
| Vue | 3.x | 前端框架（MVVM） |
| Vite | 6.x | 构建工具（:3000） |
| TypeScript | — | 类型安全 |
| Element Plus | — | UI 组件库 |
| Pinia | — | 状态管理 |
| Vue Router | 4.x | 路由 |
| Axios | — | HTTP 客户端 |
| hls.js | 1.x | HLS 播放（原生 `<video>` + `hls.js`，支持自定义请求头） |
| Canvas Danmaku | 自研 | 弹幕渲染引擎（轨道分配 / 屏蔽过滤 / 设置面板） |

### 开发工具

- **IDE**：IntelliJ IDEA（推荐）/ VS Code
- **构建**：Maven 3.8+
- **容器化**：Docker + Docker Compose
- **API 测试**：Knife4j（`/doc.html`）/ Postman / Apifox

---

## ✅ 验证（2026-10 改造后实测）

| 项目 | 命令 | 结果 |
|------|------|------|
| 全量编译 | `mvn -o -B -DskipTests clean compile` | ✅ BUILD SUCCESS 12/12 模块（33.4s，JDK 17.0.12 / Maven 3.6.1） |
| 打包 | `mvn -o -B -DskipTests package` | ✅ BUILD SUCCESS，各模块产出 jar（Docker 镜像可用，41.6s） |
| 单元测试 | `mvn -o -B test` | ✅ BUILD SUCCESS 12/12 |
| 弹幕单测（离线） | `abc-danmaku-service\run-unit-tests.ps1` | ✅ 23/23 通过 |
| 源引擎单测（离线） | `abc-source-service\run-unit-tests.ps1` | ✅ 26/26 通过 |
| 前端类型检查 | `cd abc-frontend; npx vue-tsc --noEmit` | ✅ exit 0，无类型错误 |
| 前端生产构建 | `cd abc-frontend; npm run build` | ⚠️ 需在你自己终端执行（本项目为沙箱环境禁止命名管道，esbuild 子进程 spawn EPERM，非代码问题） |
| SQL 全量导入 | 9 个脚本按 `README-DOCKER.md` §4.4 顺序执行 | ✅ 全部成功，17 张表 + 种子数据（规则 106 条 / 标签 18 条） |
| 容器化 | `docker compose up -d --build` | ⚠️ 需 Docker Desktop 已启动（见 README-DOCKER.md） |
| 压测 | `jmeter/run.ps1` | ⚠️ 需 JMeter 5.5+ 与本机服务运行中 |

> 单元测试采用离线方案：`src/unit-test/java` + `OfflineJUnitRunner`，通过 `-Punit-tests` 或脚本运行，不依赖联网拉取 JUnit 依赖。

## 🚀 快速启动

### 前置要求

| 组件 | 版本要求 | 检查命令 |
|------|----------|----------|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Node.js | 22.14+ | `node -v` |
| Docker Engine / Compose v2 | 24+ / v2.20+ | `docker version`、`docker compose version` |

### 步骤一：启动中间件

```bash
cd anime-bilibili-cloud

# 一键启动全部服务（20 个服务定义：8 中间件 + 10 业务服务 + 前端 + Bangumi 反代 + Nacos 配置作业）
# 首次约 10~20 分钟（Maven 依赖下载），之后 start_period 内约 2~4 分钟全部 healthy
docker compose up -d --build
docker compose ps          # 业务服务应为 healthy，nacos-config-init 为 exited(0)
```

### 步骤二：检查中间件

```bash
docker exec abc-mysql mysqladmin ping -h localhost -u root -proot123
docker exec abc-redis redis-cli -a redis123 ping
docker exec abc-mongodb mongosh --eval "db.runCommand('ping').ok"
curl http://localhost:9200/_cluster/health
curl http://localhost:9876/                       # RocketMQ NameServer
# Nacos 控制台: http://localhost:8848/nacos （nacos/nacos）
```

### 步骤三：初始化数据库

容器化部署下**无需手工导入**：`sql/` 下 9 个脚本已由 compose 按序挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d`，首次启动自动执行（顺序见 `README-DOCKER.md` §4.4）；MongoDB 用 `sql/mongo-init.js` 自动初始化；Nacos 共享配置由 `nacos-config-init` 一次性作业自动发布。

> 完整部署说明、端口表、验证清单与排错手册见 **[README-DOCKER.md](README-DOCKER.md)**。

### 步骤四：编译并启动后端

```bash
mvn clean install -pl abc-common          # 先安装公共模块
mvn clean install -DskipTests             # 编译整个工程

# 各服务分别启动（每个一个终端；也可在 IDEA Services 面板批量启动）
mvn spring-boot:run -pl abc-gateway         # 8080
mvn spring-boot:run -pl abc-auth-service    # 8081
mvn spring-boot:run -pl abc-user-service    # 8082
mvn spring-boot:run -pl abc-anime-service   # 8083
mvn spring-boot:run -pl abc-video-service   # 8084
mvn spring-boot:run -pl abc-danmaku-service # 8085
mvn spring-boot:run -pl abc-comment-service # 8086
mvn spring-boot:run -pl abc-search-service  # 8087（可选）
mvn spring-boot:run -pl abc-source-service  # 8088
mvn spring-boot:run -pl abc-ai-service      # 8089
```

> AI 服务需要大模型 API Key（DeepSeek 控制台申请）：
> ```bash
> export AI_API_KEY=sk-xxxx        # Windows PowerShell: $env:AI_API_KEY="sk-xxxx"
> ```
> 也可在 `abc-ai-service` 的 `application.yml` 中直接配置，并可切换为通义千问。

### 步骤五：启动前端

```bash
cd abc-frontend
npm install
npm run dev                 # http://localhost:3000
```

### 步骤六：功能验证

1. 匿名浏览首页/搜索/详情（未登录也可用），注册/登录（`/register`、`/login`）
2. 首页浏览每日放送、热门番剧与「继续观看」（按上次进度一键续播）
3. 搜索框输入关键词（本地 + Bangumi 聚合，结果 `_id` 统一为 `bgm:xxx`）
4. 进入番剧详情页 → 收藏/追番 → 选择剧集 → 播放页观看；播放失败自动换源（最多 3 个候选）
5. 观看 dandanplay 弹幕（Canvas 自研渲染，设置面板可调透明度/速度/屏蔽词），登录后发送实时弹幕（WS JWT 鉴权）
6. 评分、评论、追番，观看进度每 10s 上报，换设备自动续播
7. 点击右下角**桌宠**，对话推荐 / 番剧问答（RAG 注入历史+收藏+评分+搜索+标签）
8. 个人中心：收藏列表、搜索历史、观影记录、AI 设置
9. 管理后台（`/admin`）5 个 tab：番剧 CRUD / 剧集 / 数据源 / 规则在线测试 / 弹幕热度图

---

## 📁 项目结构

```
anime-bilibili-cloud/
├── pom.xml                     # 父 POM
├── docker-compose.yml          # 全量编排：8 中间件 + 10 业务服务 + 前端 + Bangumi 反代（20 个服务定义）
├── README-DOCKER.md            # 容器化部署指南（快速开始/端口表/验证清单/排错）
├── abc-common/                 # 公共模块（R、异常、DTO、JWT、注解）
├── abc-gateway/                # API 网关 :8080
├── abc-auth-service/           # 认证服务 :8081
├── abc-user-service/           # 用户服务 :8082
├── abc-anime-service/          # 番剧服务 :8083
│   └── datasource/             #   BangumiDataSource / Css1DataSource / 注册表
├── abc-video-service/          # 视频服务 :8084
├── abc-danmaku-service/        # 弹幕服务 :8085（WebSocket + dandanplay）
│   └── source/dandanplay/      #   DandanplayClient（签名鉴权）
├── abc-comment-service/        # 评论评分服务 :8086
├── abc-search-service/         # ES 全文检索服务 :8087
├── abc-source-service/         # 规则源引擎 :8088
│   └── engine/                 #   RuleEngine / CssStrategy / 聚合 / 导入
├── abc-ai-service/             # AI 桌宠服务 :8089
│   └── llm/                    #   LangChain4jClient（OpenAI 兼容）
├── abc-frontend/               # Vue 3 前端
│   └── src/
│       ├── views/              #   home / anime / play / search / user / admin
│       ├── components/PetAssistant.vue   # 桌宠助手
│       ├── stores/  ├── api/  └── router/
└── sql/
    ├── init.sql / abc_anime.sql          # 业务库
    ├── source_rule.sql                   # 采集规则表
    ├── animeko_rules.sql / kazumi_rules.sql  # 规则集样例
    ├── ai_chat.sql                       # AI 会话表
    ├── watch_history_v2.sql              # 观看历史
    ├── mongo-init.js                     # MongoDB 初始化
    └── nacos-config.md / nacos-config.sh
```

---

## 📋 API 接口一览（经网关 :8080 访问）

### 认证 `/api/auth`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` `/login` `/logout` `/refresh` | 注册/登录/退出/刷新 |
| GET | `/api/auth/captcha` | 验证码 |

### 番剧与数据源 `/api/anime`、`/api/datasource`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/anime/list`、`/{id}`、`/{id}/episodes` | 列表/详情/分集 |
| GET | `/api/anime/hot`、`/calendar`、`/tags` | 热门/放送表/标签 |
| GET | `/api/anime/bgm-comments` | Bangumi 热门评论 |
| GET | `/api/datasource/search`、`/status` | 多源搜索/数据源状态 |
| POST | `/api/datasource/sync/trending` `/sync/search` `/sync/full` | 同步 Bangumi 数据 |
| GET | `/api/datasource/mirror/status`、`/test` | Bangumi 镜像状态 |

### 视频源规则 `/api/source`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/source/play/{animeKey}/{ep}?title=&keyword=&ruleId=` | **一次拿全**：直链 `url`/`format`/`headers`/签名 `proxyUrl` + 候选 `candidates` + `stageUsed` |
| GET | `/api/source/select/stream` | SSE 分阶段选优（stage/candidate/final/complete） |
| GET | `/api/source/search`、`/match`、`/sources` | 多源搜索 / 选集匹配 / 源列表 |
| GET | `/api/source/proxy?ruleId=&u=&ts=&sign=&scope=` | 播放流代理（HMAC 验签 + SSRF 拦截 + m3u8 分片重写） |
| CRUD | `/api/source/rule(s)` | 规则增删改查（写操作需登录/管理员） |
| POST | `/api/source/rule/test` | 规则在线测试（`{ruleId,keyword}` 或 `{mode:"maccms",...}`） |
| POST | `/api/source/import/animeko`、`/import/kazumi` | 导入规则集 |

### 弹幕 `/api/danmaku`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/danmaku/list` | 弹幕列表（本地优先，缺省拉 dandanplay；`animeKey`+`ep` 查询） |
| GET | `/api/danmaku/window?animeKey=&ep=&startTime=&endTime=&limit=` | 时间窗弹幕（播放端分片加载，默认前后 30s） |
| GET | `/api/danmaku/heat?animeKey=&ep=` | 10s 分桶热度（管理端热度图 / 播放端高能提示） |
| GET | `/api/danmaku/stats` | flush 次数 / 缓存命中率 / 在线数 |
| GET | `/api/danmaku/online`、`/my` | 在线人数 / 我的弹幕 |
| WS | `/ws/danmaku/{animeKey}/{episodeNum}?token=<JWT>` | 实时弹幕收发（游客只收不发；上行 `text/timestamp/position/color/fontSize`） |

### 评论评分 `/api/comment`、`/api/rating`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/comment`、`/{id}/like` | 发表评论 / 点赞（身份取网关注入的 `X-User-Id`，非业主删除返回 403） |
| GET | `/api/comment/anime/{animeId}` | 番剧评论列表 |
| POST/GET | `/api/rating`、`/api/rating/anime/{animeId}`、`/api/rating/user` | 评分（upsert）/ 评分统计 / 我的评分 |

### 搜索 `/api/search`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/search/anime`、`/suggest`、`/hot` | 聚合搜索（默认）/ 联想 / 热搜 |

> `abc-search-service`（:8087）提供同构的 Elasticsearch 版本接口，将网关 `/api/search/**` 切换到该服务即可启用 ES 全文检索。

### AI 助手 `/api/ai`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/chat/stream?message=&sessionId=` | SSE 流式对话（事件：token / done / {sessionId} / usage / error） |
| GET | `/api/ai/sessions`、`/messages?sessionId=` | 会话列表 / 历史消息（校验会话归属） |
| GET | `/api/ai/model` | 当前模型与可用性 |

### 用户 `/api/user`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/PUT | `/api/user/profile` | 查看/更新资料 |
| POST/DELETE | `/api/user/follow/{userId}` | 关注/取关 |
| GET | `/api/user/history` | 观看历史（AI 推荐数据源） |
| CRUD | `/api/user/favorite` | 收藏/追番（status 1想看 2在看 3看过 4弃番） |
| GET/POST/DELETE | `/api/user/search-history` | 搜索记录 |
| POST/GET | `/api/user/progress`、`/api/user/progress?since=` | 上报进度 / 增量同步（返回 `items` + `lastSyncAt`） |
| GET/PUT | `/api/user/ai-key` | AI Key（掩码返回；内部接口 `/ai-key/raw` 需 `X-Internal-Token`） |

---

## 🗄️ 数据库设计

### MySQL

| 表 | 说明 |
|----|------|
| `user` | 用户（username, password, email, avatar, role, status） |
| `anime` / `episode` | 番剧 / 剧集（含 Bangumi 原始 id、评分、放送信息） |
| `tag` / `anime_tag` | 标签及番剧-标签关联 |
| `comment` / `rating` / `user_follow` | 评论（楼中楼）/ 评分 / 关注 |
| `watch_history` | 观看历史（含 `anime_key`/`episode_num`，见 `watch_history_v2.sql`） |
| `user_favorite` | 收藏/追番（status 1想看 2在看 3看过 4弃番，唯一键 `uk_user_anime`） |
| `user_progress` | 播放进度（秒级续播，唯一键 `uk_user_anime_ep`） |
| `search_history` | 搜索记录（归一化关键词 + 结果数 + 来源） |
| `danmaku_report` | 弹幕举报（审核状态机） |
| `video` | 视频资源（本地上传备用） |
| `source_rule` | 采集规则（资源站搜索/选择器/解析配置，106 条种子） |
| `ai_chat_session` / `ai_chat_message` | AI 会话与消息（含 `rag_snapshot`） |

### MongoDB

- 库 `abc_danmaku`，集合 `danmaku`，字段含来源标记 `source = USER / DANDANPLAY`、位置（0 滚动/1 顶/2 底）、归一化文本 `normText`。
- 索引（`sql/mongo-init.js` / `mongo-index-v2.js` 幂等创建）：`idx_window{animeKey,episodeNum,position}`、`idx_legacy{animeKey,episodeNum,timestamp}`、`idx_dedup{animeKey,episodeNum,normText,position}`、`idx_user{userId}`、`idx_blocked{blocked}`。

### Elasticsearch

- 索引 `abc_anime`：title / description / tags / year / season / type / rating_avg。

### Redis

- Token 与登录态（`refresh_token:*`）、JWT 黑名单 `blacklist:*`、验证码 `captcha:*`、热门番剧缓存、弹幕缓冲 List `danmaku:episode:*`、弹幕时间索引 ZSET（每集裁到 5 万条）、搜索热词 ZSet、在线人数与频率计数。

---

## ⚖️ 合规声明

本项目仅用于个人学习与毕业设计研究：

- 番剧元数据来自 Bangumi 公开接口，弹幕来自 dandanplay 开放接口，视频内容由第三方资源站提供，**项目本身不存储、不分发任何受版权保护的视频文件**；
- 采集规则由使用者自行配置，请遵守对应站点的服务条款与 robots 协议；
- 大模型生成内容由第三方 API 返回，不代表本项目立场；
- 请勿用于任何商业用途。

---

## 🎯 参考项目

- [open-ani/animeko](https://github.com/open-ani/animeko) — 弹幕协议、数据源与规则集设计
- [Predidit/Kazumi](https://github.com/Predidit/Kazumi) — 规则化采集与多源聚合思路
- [dandanplay 开放平台](https://api.dandanplay.net/) — 弹幕 API 与签名鉴权
- [Bangumi API](https://bangumi.github.io/api/) — 番剧元数据接口
- [DIYgod/DPlayer](https://github.com/DIYgod/DPlayer) — HTML5 弹幕视频播放器
- [xunlu129/teriteri-client](https://github.com/xunlu129/teriteri-client) — B 站风格 UI 参考

---

## 🗺️ 开发路线图

- [x] Spring Cloud 微服务骨架（网关 / 鉴权 / 注册配置）
- [x] Bangumi 元数据同步与多源聚合搜索
- [x] 规则化视频源引擎（Animeko / Kazumi 规则兼容、直链解析、流代理）
- [x] dandanplay 弹幕聚合 + WebSocket 实时弹幕（JWT 握手鉴权、Redis 缓冲、批量落库、三级匹配、时间窗/热度接口）
- [x] Elasticsearch 全文检索服务（独立部署）
- [x] AI 桌宠助手（LangChain4j + DeepSeek，流式对话、会话记忆、推荐问答）
- [x] 弹幕 RocketMQ 异步化（生产/消费 + Redis 缓冲批量，`danmaku.mq.enabled` 可开关）
- [x] P0 安全加固（网关注入防护、验证码校验、refresh 轮换、SSRF/HMAC、身份取 header）
- [x] P1 技术深度（五级择优 + SSE 分阶段选流、RAG 多元化、收藏/进度增量同步、自研弹幕引擎、管理端规则测试）
- [x] 容器化一键部署（20 服务 compose + 每服务 Dockerfile + Nacos 自动配置）
- [x] 实验与文档（压测脚本、MQ 选型/推荐效果/压测对比三份实验报告、答辩问答手册）
- [ ] Caffeine + Redis 二级缓存与 Sentinel 限流降级
- [ ] 番剧标签持久化（`tag` / `anime_tag` 同步填充，作为推荐特征）
- [ ] 收藏 / 追番状态
- [ ] 弹幕显示开关与屏蔽规则
- [ ] 网关搜索切换至 ES 服务，前端接入搜索联想与热搜
- [ ] 协同过滤 + 内容标签混合推荐，并与大模型推荐做效果对比
- [ ] 单集弹幕情绪与热点总结（大模型）
- [ ] 视频分片上传 / 转码（FFmpeg）

---

## 👨‍💻 开发信息

- **类型**：毕业设计（计算机科学与技术）
- **架构**：Spring Cloud 微服务 + 前后端分离
- **数据/中间件**：MySQL + Redis + MongoDB + Elasticsearch + Nacos（RocketMQ 接入中）
- **AI**：LangChain4j + DeepSeek（OpenAI 兼容接口）

## 📄 License

本项目仅供学习交流使用，拟采用 MIT License。
