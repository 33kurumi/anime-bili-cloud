# Docker Compose 一键部署指南

本文档说明如何用 `docker-compose.yml` 在一台机器上把 **anime-bilibili-cloud**（Spring Cloud 微服务 + Vue3 前端）连同它依赖的 8 类中间件全部拉起来，全程无需在本机安装 JDK / Node / MySQL / Redis / Nacos。

> 适用版本：Docker Desktop 4.x（Compose v2）或 Linux 上的 docker-ce + compose-plugin。`depends_on.condition: service_completed_successfully` 需要 **Compose v2.20+**。

---

## 1. 前置条件

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Docker Engine | 24+ | `docker version` 能看到 Server 端 |
| Docker Compose | v2.20+ | `docker compose version`；本仓库用 `docker compose`（无横杠），**不是** `docker-compose` v1 |
| 内存 | **≥ 8 GB 可用**（推荐 12 GB） | 堆内存预算：10 个业务服务各 512 MB + ES 512 MB + Broker 512 MB + Nacos/NameServer 各约 256 MB，其余为容器与页缓存开销 |
| 磁盘 | ≥ 15 GB | 镜像（maven 构建层较大）+ 数据卷 |
| 网络 | 首次构建需联网 | 拉取基础镜像与 Maven 依赖；之后可离线重建 |
| 可选 | 一个可用的 DeepSeek API Key | 不填也能启动，AI 桌宠会降级提示「未配置」 |

**CPU 架构**：镜像均基于官方多架构镜像（`maven:3.9-eclipse-temurin-17`、`node:20-alpine`、`nginx:1.27-alpine`），arm64（Apple Silicon）可直接跑；但 Maven 中央仓库在 arm 上构建更慢，首次构建请耐心。

---

## 2. 三分钟快速开始

```bash
# 1) 进入项目根（docker-compose.yml 所在目录）
cd dzy/anime-bilibili-cloud

# 2) 准备环境变量（可选；不建 .env 时 compose 使用内置默认值）
cp .env.example .env
# 按需编辑 .env：改三个中间件密码、填 AI_API_KEY / DANDANPLAY_APP_ID+SECRET

# 3) 一键构建并启动（首次约 10~20 分钟，主要是 mvn 依赖下载）
docker compose up -d --build

# 4) 等待全部 healthy（约 2~4 分钟）
docker compose ps

# 5) 打开前端
#    http://localhost:3000
```

启动完成后可以访问：

| 地址 | 内容 |
| --- | --- |
| http://localhost:3000 | 前端（Vue3 SPA，nginx 托管并反代 /api、/ws、/uploads） |
| http://localhost:8080/api/** | 网关直连入口（调试用） |
| http://localhost:8848/nacos | Nacos 控制台（账号/密码 `nacos` / `nacos`） |
| http://localhost:8090 | Bangumi 元数据反代网关（可选组件） |

---

## 3. 容器与端口清单（共 20 个服务定义：19 个常驻容器 + 1 个一次性作业）

### 3.1 基础设施（8 个）

| 容器 | 镜像 | 宿主机端口 | 数据卷 / 说明 |
| --- | --- | --- | --- |
| `abc-mysql` | mysql:8.0.33 | **3307**→3306 | `mysql_data`；首次启动按文件名顺序自动执行 `sql/` 下 9 个初始化脚本（见 4.4）。用 3307 是为了不和本机已装的 MySQL 冲突 |
| `abc-redis` | redis:5.0.14-alpine | 6379 | `redis_data`；AOF 持久化，密码见 `.env` 的 `REDIS_PASSWORD` |
| `abc-mongodb` | mongo:5.0.5 | 27017 | `mongo_data`；存弹幕集合 |
| `abc-elasticsearch` | elasticsearch:7.17.27 | 9200 / 9300 | `es_data`；单节点、`ES_JAVA_OPTS=-Xms512m -Xmx512m`、关闭安全认证（仅用于本地/答辩演示） |
| `abc-rmq-namesrv` | apache/rocketmq:5.1.4 | 9876 | RocketMQ 5.x NameServer（堆 256 MB） |
| `abc-rmq-broker` | apache/rocketmq:5.1.4 | 10909 / 10911 | 单 master、`--enable-proxy`、自动建 Topic（堆 512 MB）；弹幕异步落库用 |
| `abc-nacos` | nacos/nacos-server:v2.3.1 | 8848 / 9848 | 单机模式 + 内嵌 Derby；注册中心 + 配置中心 |
| `abc-nacos-config-init` | nacos/nacos-server:v2.3.1 | — | **一次性作业**：把 `docker/nacos/*.yaml` 发布为共享配置后退出（退出码 0） |

### 3.2 业务服务（10 个）+ 前端（1 个）+ Bangumi 反代（1 个）

| 容器 | 端口 | 职责 |
| --- | --- | --- |
| `abc-gateway` | 8080 | 唯一对外入口：路由 + 全局 JWT 鉴权 + 身份头注入 + 黑名单校验 |
| `abc-auth-service` | 8081 | 注册 / 登录 / 刷新 / 登出 / 验证码 |
| `abc-user-service` | 8082 | 资料、关注、观看历史、收藏、进度、搜索历史、AI 配置 |
| `abc-anime-service` | 8083 | 番剧元数据、Bangumi 同步与镜像、搜索聚合 |
| `abc-video-service` | 8084 | 视频元数据、播放地址、上传与静态托管 |
| `abc-danmaku-service` | 8085 | 弹幕 WebSocket + 缓冲 + 批量落库 + MQ + 限流去重 |
| `abc-comment-service` | 8086 | 评论与评分 |
| `abc-search-service` | 8087 | Elasticsearch 全文检索 |
| `abc-source-service` | 8088 | 规则化视频源引擎（规则 CRUD / 在线测试 / 流代理） |
| `abc-ai-service` | 8089 | AI 桌宠（LangChain4j + SSE 流式） |
| `abc-frontend` | **3000**→80 | Vue3 产物 + nginx（`docker/nginx/frontend.conf`） |
| `abc-bgm-gateway` | 8090 | Bangumi 反代（`docker/nginx/bangumi.conf`），规避境外源不稳定 |

> 业务端口为了排障方便全部映射到宿主机。**答辩演示时建议只暴露 3000**，可在 `docker-compose.yml` 里注释掉其余 `ports`。

---

## 4. 配置项

### 4.1 `.env`（可选，17 行模板见 `.env.example`）

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | `root123` | MySQL root 密码 |
| `REDIS_PASSWORD` | `redis123` | Redis 密码 |
| `MONGO_ROOT_PASSWORD` | `mongo123` | MongoDB 密码 |
| `AI_API_KEY` | 空 | DeepSeek Key；空则 AI 桌宠降级（不阻塞其它模块） |
| `DANDANPLAY_APP_ID` / `DANDANPLAY_APP_SECRET` | 空 | 弹弹play 开放平台凭据；空则只显示本地弹幕 |

密码默认值与 `sql/nacos/abc-common.yaml`、各服务 `application.yml` 的默认值**保持一致**，所以不建 `.env` 也能跑通；改了 `.env` 之后，容器内服务通过 Nacos 共享配置读取的密码也需要同步（`docker/nacos/abc-common.yaml` 与 `abc-datasource.yaml` 用的是 `nacos` 占位，compose 通过环境变量覆盖，见 4.2）。

### 4.2 环境变量如何进容器

`docker-compose.yml` 顶部定义了三个 YAML 锚点，被各服务复用：

```yaml
x-env-common: &env-common          # TZ / JAVA_OPTS / Nacos 地址
x-env-mysql:  &env-mysql           # SPRING_DATASOURCE_*
x-env-redis:  &env-redis           # SPRING_DATA_REDIS_*
x-hc:         &hc                  # healthcheck 公共参数（interval/timeout/retries/start_period）
```

含义：服务内的 Spring 配置以 Nacos 共享配置（`abc-common.yaml`、`abc-datasource.yaml`）为底，**环境变量优先级更高**，因此 compose 里注入的地址（`mysql:3306`、`redis:6379`、`nacos:8848`）会覆盖 Nacos 中写的 `127.0.0.1`。这也是同一份 jar 既能本机跑又能容器跑的原因。

### 4.3 弹幕 MQ 开关

`abc-danmaku-service` 在 compose 中显式设置：

```yaml
DANMAKU_MQ_ENABLED: "true"   # docker-compose.yml:438
```

打开后弹幕走「Redis 缓冲 → RocketMQ → 消费端批量落库」，MQ 不可用时 FlushTask 自动降级为本地落库（`DanmakuMessageProducer.available()` 判断）。该变量同时是压测对照实验的开关（见 `jmeter/README.md` §6）。

---

### 4.4 数据库初始化脚本（执行顺序不可乱）

`sql/` 目录被逐个挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d`，**按文件名前缀顺序**执行，且只在数据卷为空时执行一次：

| 顺序 | 脚本 | 内容 |
| --- | --- | --- |
| 01 | `init.sql` | 建库与基础账号 |
| 02 | `abc_anime.sql` | 核心业务表（用户/番剧/剧集/标签/评论/评分/关注/观看历史/视频） |
| 03 | `watch_history_v2.sql` | 观看历史表结构增量 |
| 04 | `ai_chat.sql` | AI 会话与消息表 |
| 05 | `ai_user_settings.sql` | 用户 AI 配置加列 |
| 06 | `source_rule.sql` | 规则化视频源规则表 + 种子 |
| 07 | `03_platform_extension.sql` | 收藏 / 搜索历史 / 观看进度 / 弹幕举报 |
| 08 | `kazumi_rules.sql` | Kazumi 规则集导入 |
| 09 | `animeko_rules.sql` | Animeko 规则集导入 |

MySQL 容器的环境变量还预置了：`MYSQL_DATABASE=abc_anime`、业务账号 `abc/abc123`（`docker-compose.yml:58-62`）。业务服务默认用 root + `.env` 里的密码连接，账号仅备运维使用。

想重新初始化数据库：`docker compose down -v` 后重新 `up`（会清空所有数据卷，包括 Nacos 与 Mongo）。

---
## 5. 启动顺序（由 depends_on 自动保证）

```text
mysql / redis / mongodb / elasticsearch / rocketmq-namesrv
        │  (healthy)
        ▼
rocketmq-broker ──(healthy)──┐
                             ▼
                          nacos (healthy)
                             │
                             ▼
              nacos-config-init (completed successfully)
                             │
                             ▼
   10 个业务微服务（各自 healthcheck：TCP 端口可连）
                             │
                             ▼
                   abc-frontend / abc-bgm-gateway
```

说明：
- 业务服务的 healthcheck 用 `bash -c '</dev/tcp/127.0.0.1/<port>'` 做 TCP 探活（项目没引 actuator，避免为了探活而加依赖）；
- `start_period: 90s` 给 Spring 冷启动留足时间，避免刚起就判失败；
- `nacos-config-init` 退出码非 0 时，后面的业务服务**不会启动**，这是刻意的 fail-fast。

---

## 6. 启动后验证清单

按顺序执行，全绿说明部署成功：

```bash
# ① 容器状态：应看到 20 项，业务服务均为 healthy，nacos-config-init 为 exited(0)
docker compose ps

# ② 网关存活 + 匿名可访问的番剧列表
curl -s http://localhost:8080/api/anime/list?page=1&size=3 | head -c 400
#    期望：{"code":200,...,"records":[...]}

# ③ 鉴权链路：未带 token 访问受保护接口应 401
curl -i -s http://localhost:8080/api/user/profile | head -5
#    期望：HTTP/1.1 401 + {"code":401,...}

# ④ 注册 + 登录拿 token
curl -s -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo1234","email":"d@e.com"}'
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo1234"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s http://localhost:8080/api/user/profile -H "Authorization: Bearer $TOKEN" | head -c 300

# ⑤ 弹幕列表（匿名可读）
curl -s 'http://localhost:8080/api/danmaku/list?animeKey=bgm:10380&ep=1&pullRemote=false' | head -c 300

# ⑥ 前端页面可打开
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000
#    期望：200

# ⑦ AI 服务（未配 Key 时应返回明确的未配置提示，而不是 500）
curl -s -X POST http://localhost:8080/api/ai/chat -H 'Content-Type: application/json' -d '{"message":"你好"}' | head -c 300
```

WebSocket 弹幕需要浏览器或 `wscat` 验证（脚本方式见 `jmeter/README.md`）：

```bash
# 需安装 websocat：https://github.com/vi/websocat
websocat 'ws://localhost:3000/ws/danmaku/bgm:10380/1?token=<你的accessToken>'
```

---

## 7. 常见问题（按出现频率排序）

### Q1 `docker compose up` 卡在构建某服务，报 `Could not resolve dependencies`

构建阶段用的是 `maven:3.9-eclipse-temurin-17`，默认走 Maven 中央仓库。国内网络建议在 compose 里给构建加镜像（或在本机 `~/.m2/settings.xml` 配好后再构建，但容器内不读取宿主机 `~/.m2`）。快速验证：单独构建一个模块看报错。

```bash
docker compose build abc-gateway 2>&1 | tail -30   # 单模块构建，报错更聚焦
```

### Q2 容器状态 `unhealthy`，日志里是 `Connection refused: nacos`

业务服务比 Nacos 起得早，或 Nacos 未 healthy 就启动了业务。检查顺序：

```bash
docker compose ps nacos                               # 必须 healthy
docker compose logs nacos --tail 100 | grep -i 'Nacos started'
docker compose logs abc-gateway --tail 100 | grep -i 'nacos'
```

Nacos 自身需要 60~90 秒（单机模式 + 内嵌 Derby 建库）。若反复失败，调大 `x-hc.start_period` 或先 `docker compose up -d nacos` 等它 healthy 再整体起。

### Q3 服务起来了但接口 404 / 端口直连 401

- 走网关：`http://localhost:8080/api/...`；前端走同源相对路径，由 nginx 转发到 `abc-gateway:8080`；
- **不要**用 `http://localhost:8083/...` 直连业务服务做业务验证：网关会注入 `X-User-Id` 等身份头，绕过网关则这些头缺失，鉴权与权限判断会走不同分支（这也是本项目已知的安全弱点，答辩问答手册 Q5 有说明）；
- `/ws/**` 由网关以 `lb:ws://` 方式转发，直连 8085 也可以，但容器内前端走的是 nginx 的 `/ws/`。

### Q4 端口被占用：`Bind for 0.0.0.0:3307 failed`

本项目 MySQL 特意用 **3307** 避开本机 MySQL。若 3307 也被占，改 `docker-compose.yml` 里的 `"3307:3306"` 左侧端口，并同步 `.env`/Nacos 里的连接串（容器内部之间仍用 `mysql:3306`，不受影响）。同理 8080/8848/9200 被占时改左侧端口。

### Q5 Elasticsearch 反复重启 / 日志 `max virtual memory areas vm.max_map_count too low`

Linux 宿主机需：

```bash
sudo sysctl -w vm.max_map_count=262144
# 永久生效：echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf
```

Docker Desktop（Windows/macOS）默认已满足。若是内存不足（OOMKilled），调小 ES 的 `ES_JAVA_OPTS` 或关掉不需要的服务。

### Q6 前端能打开但接口全 502

nginx（`docker/nginx/frontend.conf`）把 `/api/` 转发到 `abc-gateway:8080`。502 说明网关没起来或不在同一网络。

```bash
docker compose ps abc-gateway
docker exec abc-frontend wget -qO- http://abc-gateway:8080/api/anime/hot | head -c 200
docker network inspect abc-anime_abc-network --format '{{range .Containers}}{{.Name}} {{end}}'
```

### Q7 SSE（AI 对话）不是流式、一次性吐出

前端 nginx 已对 `/api/` 关闭 `proxy_buffering` 并放宽 `proxy_read_timeout 300s`（`docker/nginx/frontend.conf:40-42`）。若你自行改过 nginx 配置，注意 `gzip` 与 `proxy_buffering` 都会打断 SSE 的逐字输出。

### Q8 弹幕连不上 WebSocket

1. 路径必须是 `/ws/danmaku/{animeKey}/{episodeNum}`，经 nginx 的 `/ws/` → `abc-danmaku-service:8085`；
2. nginx 必须带 `Upgrade`/`Connection: upgrade` 头（已在 `frontend.conf:45-52` 配好）；
3. 未登录用户默认**不允许发弹幕**（`danmaku.send.allow-guest-send: false`），只读可以；
4. 用 `docker compose logs abc-danmaku-service --tail 100` 看握手日志。

### Q9 弹幕没走 MQ（日志里没有 “MQ 投递”）

检查两处：compose 里 `DANMAKU_MQ_ENABLED` 是否为 `"true"`；RocketMQ broker 是否 healthy。MQ 不可用时设计上会**自动降级本地落库**，不会丢弹幕，但压测对照实验的「异步 vs 同步」就没有区分度了。

```bash
docker compose exec abc-danmaku-service env | grep DANMAKU
docker compose logs abc-rmq-broker --tail 50 | grep -i 'boot success'
```

### Q10 Windows 下脚本/中文乱码

`docker/init-nacos.sh` 是 sh 脚本，由 Linux 容器执行，与 Windows 无关。若你在 Windows 上自己写了 `.ps1` 辅助脚本，**必须存成 UTF-8 with BOM**，否则 Windows PowerShell 5.1 会按 GBK 解析导致「字符串缺少终止符」之类的语法错误（`jmeter/README.md` 有同样提示）。

---

## 8. 常用运维命令

```bash
# 查看状态 / 资源占用
docker compose ps
docker stats --no-stream

# 跟踪某个服务日志（微服务日志量大，务必 --tail）
docker compose logs -f --tail 200 abc-gateway

# 只重启一个服务（改了 jar 需先重新 build）
docker compose up -d --build abc-danmaku-service

# 进容器排查
docker compose exec abc-mysql mysql -uroot -proot123 -e 'show databases;'
docker compose exec abc-redis redis-cli -a redis123 ping
docker compose exec abc-elasticsearch curl -s localhost:9200/_cat/indices?v

# 停止（保留数据）
docker compose stop

# 停止并删除容器 + 网络（数据卷保留）
docker compose down

# 彻底清理（含数据卷，慎用：会删库）
docker compose down -v
```

---

## 9. 目录结构（部署相关）

```text
anime-bilibili-cloud/
├── docker-compose.yml          # 20 个服务定义（8 中间件 + 10 业务 + 前端 + bgm 反代 + 一次性作业）
├── .env.example                # 环境变量模板（复制为 .env）
├── .dockerignore               # 构建上下文过滤（target/node_modules/.git 等）
├── abc-*/Dockerfile            # 11 个镜像（10 业务服务 + 前端），全部多阶段构建
├── docker/
│   ├── init-nacos.sh           # 把 yaml 发布到 Nacos 的幂等脚本（nacos-config-init 作业执行）
│   ├── nacos/
│   │   ├── abc-common.yaml     # 共享配置：Redis、JWT、MyBatis-Plus 等
│   │   └── abc-datasource.yaml # 共享配置：MySQL 数据源、Druid
│   └── nginx/
│       ├── frontend.conf       # 前端静态托管 + /api /ws /uploads 反代
│       └── bangumi.conf        # Bangumi 元数据反代
├── sql/                        # 建表脚本（mysql 容器首次启动自动执行）+ nacos/ 下的共享配置
└── jmeter/                     # 压测脚本（run.ps1 一键跑，见 jmeter/README.md）
```

---

## 10. 与「本地开发模式」的区别

| 维度 | 本地开发 | Docker Compose |
| --- | --- | --- |
| 中间件 | 本机装 MySQL/Redis/Nacos/…，用 `127.0.0.1` 连接 | 容器内用服务名（`mysql`/`redis`/`nacos`），由 compose 环境变量覆盖 |
| 启动 | 先起中间件 → 再逐个 `mvn spring-boot:run` → `npm run dev` | 一条 `docker compose up -d --build` |
| 前端 | Vite dev server `:3000`，`vite.config.ts` 的 proxy 转发 `/api` `/ws` | nginx `:3000` 托管构建产物，`frontend.conf` 反代（与 dev proxy **保持同构**） |
| 配置来源 | Nacos `127.0.0.1:8848` + 本地 `application.yml` | Nacos `nacos:8848` + 环境变量覆盖 |
| 适用场景 | 改代码热重载 | 答辩演示、交付验收、压测 |

---

## 11. 安全提示（务必在答辩前处理）

1. **改默认密码**：`.env` 里三个中间件密码、Nacos 的 `nacos/nacos` 都是演示值；
2. **减少端口暴露**：`.env` 与 compose 只保留 `3000`（前端）即可，`3307/6379/27017/9200/8848` 一律不加映射，避免被扫描；
3. **API Key 不入库**：`AI_API_KEY` 只放 `.env`（已被 `.gitignore` 忽略），不要写进 `application.yml` 提交；
4. **ES 关闭了认证**（`xpack.security.enabled=false`），仅限内网/本机，不要部署到公网；
5. 已知架构弱点（端口直连绕过网关、SSRF 未完整防护等）见 `docs/答辩问答手册.md` Q5/Q10，答辩时主动说明比被追问更好。

---

## 12. 卸载 / 重置

```bash
# 彻底删除（含镜像与数据卷）
docker compose down -v --rmi local

# 只删数据卷（下次启动重新初始化数据库与 Nacos 配置）
docker compose down -v
```

数据库初始化脚本的执行顺序与内容见 §4.4；**只有在数据卷为空时才会执行**，想重跑建表必须先 `down -v`。
