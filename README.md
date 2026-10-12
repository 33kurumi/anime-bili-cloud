# 🎬 Anime Bilibili Cloud (ABC)

> 毕业设计项目 —— 仿 B 站风格的番剧观看与弹幕互动平台，基于 **Spring Boot 3 + Spring Cloud 微服务架构**，集成大语言模型智能助手。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.1-blue)](https://spring.io/projects/spring-cloud)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3.x-42b883)](https://vuejs.org/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.0.0-purple)](https://docs.langchain4j.io/)

---

## 📖 项目简介

Anime Bilibili Cloud（简称 **ABC**）是一个前后端分离的番剧（动漫）网站，采用 Spring Cloud 微服务架构。系统不直接存储影视内容，而是通过对接公开接口与**可配置的采集规则**完成番剧元数据聚合、视频直链解析与弹幕聚合，并在此基础上引入大语言模型"桌宠"助手，实现对话式番剧推荐与观剧交流。

四大核心能力：

- **📚 番剧元数据聚合**：对接 [Bangumi](https://bgm.tv/) 开放接口，获取封面、名称、评分、简介、分集、每日放送与热门评论，支持本地入库与多源聚合搜索（标签字段的持久化在路线图中规划）。
- **🧩 规则化视频源引擎**：参考 [Animeko](https://github.com/open-ani/animeko) 与 [Kazumi](https://github.com/Predidit/Kazumi) 的规则思想，将各资源站的搜索/解析逻辑抽象为可配置规则（数据库存储、热更新），支持多源聚合、按可用性排序、故障切换与播放流代理。
- **💬 弹幕聚合与互动**：对接 [dandanplay（弹弹 play）](https://www.dandanplay.net/) 按集匹配弹幕并缓存至 MongoDB，同时支持登录用户通过 WebSocket 实时发送弹幕，两路弹幕合并展示。
- **🐾 AI 桌宠智能助手**：基于 LangChain4j 接入 DeepSeek 大模型，以页面内嵌"桌宠"为入口，结合用户观看历史、评分与番剧元数据（RAG 检索增强），提供 SSE 流式对话式推荐、番剧问答与多轮交流。

### 核心功能

| 模块 | 功能 |
|------|------|
| 🔐 账号相关 | 注册、登录(JWT)、退出、Token 刷新、验证码 |
| 🔍 找番 | 分类浏览、每日放送、热门排行、聚合搜索（本地 MySQL + Bangumi 实时；另有独立 Elasticsearch 检索服务，可切换） |
| 📺 看番 | 规则引擎多源解析直链、DPlayer 播放器、dandanplay 弹幕、WebSocket 实时弹幕 |
| 💬 互动 | 评分(1-10 分)、评论(楼中楼)、点赞、关注、观看历史 |
| 🐾 AI 助手 | 桌宠形象、流式对话、对话式推荐、番剧问答、多轮会话与偏好记忆 |
| 🛠️ 管理后台 | 番剧 CRUD、剧集管理、采集规则管理与规则在线测试 |

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
 MySQL8   Redis   MongoDB  Elasticsearch  RocketMQ🚧   Nacos
 业务数据  缓存    弹幕存储    全文索引      (规划中)    注册/配置
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

---

## 🧩 核心设计

### 1. 规则化视频源引擎（abc-source-service）

- 规则以数据库表 `source_rule` 存储，字段包含资源站名称、搜索 URL、列表/选集/播放地址选择器（CSS/XPath）、请求头等，支持后台增删改查与**在线测试**（`/api/source/test`）。
- 支持导入 Animeko / Kazumi 规则集（`/api/source/import/animeko`、`/import/kazumi`，对应 `sql/animeko_rules.sql`、`sql/kazumi_rules.sql`）。
- 播放流程：`关键词搜索 → 选集匹配 → 播放地址解析 → 多源按可用性择优 / 故障切换`，并提供 `/api/source/proxy` 流代理处理跨域与防盗链。
- 新增资源源只需添加规则，无需改动业务代码。

### 2. 弹幕系统（abc-danmaku-service）

- **协议**：JSON over WebSocket，端点 `/ws/danmaku/{animeKey}/{episodeNum}`。
- **来源**：① dandanplay 按番剧/集数签名匹配拉取；② 登录用户实时发送（弹幕实体带 `USER / DANDANPLAY` 来源标记）。
- **懒加载**：`GET /api/danmaku/list` 先查本地 MongoDB，为空则从 dandanplay 拉取并落库缓存，带幂等去重。
- **展示**：两路弹幕合并后按视频时间轴交给 DPlayer 渲染（显示开关、屏蔽规则等在路线图中规划）。

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
| RocketMQ | 5.1.4 | 消息队列（容器与依赖已就绪，业务接入开发中 🚧） |
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
| DPlayer | 1.27.0 | 弹幕视频播放器 |

### 开发工具

- **IDE**：IntelliJ IDEA（推荐）/ VS Code
- **构建**：Maven 3.8+
- **容器化**：Docker + Docker Compose
- **API 测试**：Knife4j（`/doc.html`）/ Postman / Apifox

---

## 🚀 快速启动

### 前置要求

| 组件 | 版本要求 | 检查命令 |
|------|----------|----------|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Node.js | 22.14+ | `node -v` |
| Docker / Docker Compose | 20+ | `docker --version` |

### 步骤一：启动中间件

```bash
cd anime-bilibili-cloud

# 启动 MySQL / Redis / MongoDB / Elasticsearch / RocketMQ / Nacos / 网关(Nginx)
docker-compose up -d
docker-compose ps          # 等待约 1-2 分钟，检查各容器状态
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

依次执行 `sql/` 目录脚本：`init.sql` → `abc_anime.sql` → `source_rule.sql`
（或直接导入 `animeko_rules.sql` / `kazumi_rules.sql` 规则集）→ `ai_chat.sql`；
MongoDB 初始化使用 `sql/mongo-init.js`。Nacos 配置参考 `sql/nacos-config.md`。

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

1. 注册/登录（`/register`、`/login`）
2. 首页浏览每日放送与热门番剧
3. 搜索框输入关键词（聚合搜索）
4. 进入番剧详情页 → 选择资源源与剧集 → 播放页观看
5. 观看 dandanplay 弹幕，登录后发送实时弹幕
6. 评分、评论、关注、产生观看历史
7. 点击右下角**桌宠**，对话推荐 / 番剧问答
8. 管理后台（`/admin`）维护番剧与采集规则

---

## 📁 项目结构

```
anime-bilibili-cloud/
├── pom.xml                     # 父 POM
├── docker-compose.yml          # 中间件 + 网关编排
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
| GET | `/api/source/search`、`/match`、`/sources`、`/play` | 多源搜索/选集/直链解析 |
| GET | `/api/source/proxy` | 播放流代理（防盗链/跨域） |
| CRUD | `/api/source/rule(s)` | 规则增删改查 |
| POST | `/api/source/import/animeko`、`/import/kazumi` | 导入规则集 |
| GET | `/api/source/test`、`/test/rule/{id}` | 规则在线测试 |

### 弹幕 `/api/danmaku`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/danmaku/list` | 弹幕列表（本地优先，缺省拉 dandanplay；`animeKey`+`ep` 查询） |
| GET | `/api/danmaku/online`、`/my` | 在线人数 / 我的弹幕 |
| WS | `/ws/danmaku/{animeKey}/{episodeNum}` | 实时弹幕收发 |

### 评论评分 `/api/comment`、`/api/rating`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/comment`、`/{id}/like` | 发表评论 / 点赞 |
| GET | `/api/comment/anime/{animeId}` | 番剧评论列表 |
| POST/GET | `/api/rating`、`/api/rating/anime/{animeId}` | 评分 / 评分统计 |

### 搜索 `/api/search`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/search/anime`、`/suggest`、`/hot` | 聚合搜索（默认）/ 联想 / 热搜 |

> `abc-search-service`（:8087）提供同构的 Elasticsearch 版本接口，将网关 `/api/search/**` 切换到该服务即可启用 ES 全文检索。

### AI 助手 `/api/ai`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/chat/stream?message=&sessionId=` | SSE 流式对话 |
| GET | `/api/ai/sessions`、`/messages?sessionId=` | 会话列表 / 历史消息 |

### 用户 `/api/user`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/PUT | `/api/user/profile` | 查看/更新资料 |
| POST/DELETE | `/api/user/follow/{userId}` | 关注/取关 |
| GET | `/api/user/history` | 观看历史（AI 推荐数据源） |

---

## 🗄️ 数据库设计

### MySQL

| 表 | 说明 |
|----|------|
| `user` | 用户（username, password, email, avatar, role, status） |
| `anime` / `episode` | 番剧 / 剧集（含 Bangumi 原始 id、评分、放送信息） |
| `tag` / `anime_tag` | 标签及番剧-标签关联 |
| `comment` / `rating` / `user_follow` | 评论（楼中楼）/ 评分 / 关注 |
| `watch_history` | 观看历史（v2 见 `watch_history_v2.sql`） |
| `video` | 视频资源（本地上传备用） |
| `source_rule` | 采集规则（资源站搜索/选择器/解析配置） |
| `ai_chat_session` / `ai_chat_message` | AI 会话与消息记录 |

### MongoDB

- 库 `abc_danmaku`，集合 `danmaku`，索引 `{ animeKey, episodeNum, timestamp }`，字段含来源标记 `source = USER / DANDANPLAY`。

### Elasticsearch

- 索引 `abc_anime`：title / description / tags / year / season / type / rating_avg。

### Redis

- Token 与登录态、热门番剧缓存、弹幕热数据、搜索热词 ZSet、在线人数与频率计数。

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
- [x] dandanplay 弹幕聚合 + WebSocket 实时弹幕
- [x] Elasticsearch 全文检索服务（独立部署）
- [x] AI 桌宠助手（LangChain4j + DeepSeek，流式对话、会话记忆、推荐问答）
- [ ] 弹幕 RocketMQ 异步化（生产者/消费者，替换同步落库）
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
