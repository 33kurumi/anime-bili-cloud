# JMeter 压测套件（REST + 弹幕 WebSocket）

> 目录：`jmeter/`　|　被测系统：`abc-*微服务 + abc-frontend`　|　配套报告：`docs/实验报告-压测对比.md`
>
> 本目录包含"能直接跑"的压测脚本与一键执行脚本。**注意**：脚本编写时本机未安装 JMeter，因此 JMX 只做了
> XML 结构校验（元素/属性/层级），未做真实压测——第一次运行请按下面的"首次运行检查清单"逐项确认。

## 1. 文件清单

| 文件 | 用途 |
| --- | --- |
| `danmaku-test.jmx` | 弹幕 WebSocket 压测：开连接 → 发弹幕 → 收广播，支持 200/500/1000 并发档 |
| `rest-test.jmx` | REST 接口压测：匿名浏览链路（网关+番剧列表+搜索+详情+弹幕列表），附默认禁用的"登录+个人中心"线程组 |
| `run.ps1` | 一键跑多档并发、每档独立输出 jtl/HTML 报告，并从 jtl 汇总 TPS/P95/错误率为 CSV + Markdown 表格 |
| `results/` | 运行后自动生成（已在 .gitignore 中忽略） |

两个 JMX 的所有关键参数都能用 `-J` 覆盖，不需要改脚本：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-Jhost` / `-Jws_host` | 127.0.0.1 | 被测主机（REST / WebSocket 可分开） |
| `-Jport` / `-Jws_port` | 8080 / 8085 | 网关端口 / 弹幕服务端口 |
| `-Jthreads` | 200（弹幕）、50（REST） | 并发线程数 |
| `-Jramp` / `-Jduration` | 10 / 120 | 加压时长、持续时间（秒） |
| `-Janime_key` / `-Jepisode` | bgm:10380 / 1 | 弹幕房间 |
| `-Jpacing_ms` | 1000 | 每个用户两次发弹幕的间隔（模拟真人打字节奏） |
| `-Jkeyword` | 芙莉莲 | 搜索接口关键词 |
| `-Jthink_ms` | 300 | REST 场景思考时间 |

## 2. 环境准备

1. **JMeter 5.6.x**：解压后设置 `JMETER_HOME`，或把 `bin` 加进 `PATH`。
2. **WebSocket 插件**（只有弹幕场景需要）：把 `jmeter-websocket-samplers` 的 jar（含依赖 `tyrus`/`javax.websocket`）
   放进 `%JMETER_HOME%\lib\ext`，重启 JMeter。插件官方仓库：<https://github.com/Luminis-Arnhem/jmeter-websocket-samplers>
   （注意：社区旧文章里写的 `PeterDoornbosch/jmeter-websocket-samplers` 已不存在）。
   也可用 JMeter Plugins Manager 搜 "WebSocket Samplers by Peter Doornbosch / Luminis" 安装。
3. **Windows 编码**：本目录 `.ps1` 保存为 **UTF-8 with BOM**。Windows PowerShell 5.1 会把无 BOM 的 UTF-8
   当成 GBK 读，导致中文注释把脚本解析坏（`Parser::ParseFile` 报"字符串缺少终止符"就是这个原因）。
   用 PowerShell 7（`pwsh`）则两种都能读。**新增 ps1 时务必保持 BOM。**
4. **被测系统就绪**：`docker compose up -d` 后 10 个服务都 healthy，且数据库里有番剧数据
   （否则番剧列表恒为空、搜索恒空，压出来的是"空查询"性能）。
   弹幕房间 `bgm:10380` 第 1 集需要至少有一条历史弹幕，否则 `/api/danmaku/list` 会触发远端拉取，测到的是 ddplay 网络耗时。

## 3. 运行

```powershell
# 单档快速验证（先跑 10 线程 30 秒，确认无 4xx/5xx 再上量）
pwsh ./jmeter/run.ps1 -Scenario rest -Levels 10 -Duration 30

# 正式三档（REST 每档线程数自动减半，避免压测机先成为瓶颈）
pwsh ./jmeter/run.ps1 -Scenario all -Levels 200,500,1000 -Duration 120

# 只跑弹幕 WebSocket
pwsh ./jmeter/run.ps1 -Scenario danmaku -Levels 500 -Duration 120 -HostName 192.168.1.10
```

也可直接手跑单个脚本（更灵活，例如指定报告目录）：

```powershell
jmeter -n -t jmeter/rest-test.jmx -l jmeter/results/rest-100.jtl -e -o jmeter/results/rest-100/html -Jthreads=100 -Jduration=120
jmeter -n -t jmeter/danmaku-test.jmx -l jmeter/results/danmaku-500.jtl -e -o jmeter/results/danmaku-500/html -Jthreads=500 -Jduration=120
```

`run.ps1` 跑完会打印并落盘：

- `results/summary.csv`：每个接口一行（场景/并发/样本数/TPS/平均/P95/最大/错误率）
- `results/summary.md`：同上的 Markdown 表格，可直接粘贴进 `docs/实验报告-压测对比.md`
- `results/<场景>-<并发>/html/index.html`：JMeter 官方 HTML 仪表盘（含响应时间分布、TPS 曲线）

## 4. 首次运行检查清单

- [ ] 200 并发 10 秒跑通，错误率 0%（`results/rest-10/result.jtl` 里 `success` 全 true）
- [ ] 响应体确实是业务数据：`/api/anime/list` 的 `data.records` 非空（空库会严重低估耗时）
- [ ] 弹幕场景能连上：jtl 里 `01 连接弹幕房间` 的 responseCode 为 101/200；若是 404，检查 `-Janime_key`、
      `-Jepisode` 与 `DanmakuWebSocketServer` 的 `@ServerEndpoint("/ws/danmaku/{animeKey}/{episodeNum}")` 是否一致
- [ ] 压测机 CPU 未跑满（否则先换机或改用 JMeter 分布式压测）
- [ ] 被测服务日志级别为 INFO（DEBUG 会显著影响吞吐），JVM 参数固定为 compose 里的 `JAVA_OPTS`

## 5. 指标口径（写进论文时按这个定义）

| 指标 | 口径 | 取数来源 |
| --- | --- | --- |
| 样本数 | 该档位内该接口的完成请求数 | jtl 行数 |
| 吞吐 TPS | 样本数 ÷ (最后一个样本时间戳 − 第一个时间戳)，即实际完成速率 | run.ps1 汇总 |
| 平均响应时间 | 全部样本 `elapsed` 算术平均 | jtl `elapsed` 字段 |
| P95 响应时间 | `elapsed` 升序取第 ⌈0.95n⌉ 个 | run.ps1 汇总 |
| 错误率 | `success=false` 样本占比（4xx/5xx/超时/连接失败都算） | jtl `success` 字段 |

> `elapsed` 不含 JMeter 自身的断言与思考时间；TPS 用时间戳跨度算，隐含排除了 ramp-up 前段低负载影响，
> 答辩时说明这一点比直接拿"总请求数/总时长"更严谨。

## 6. 改造前后对照实验设计（论文第 5 章可直接用）

**目的**：用数据回答"为什么要做 RocketMQ 异步落库 / 二级缓存 / 房间分片广播"，而不是只写"看网上说这样好"。

### 6.1 控制变量

| 变量 | 处理方式 |
| --- | --- |
| 硬件 | 同一台机器（记录 CPU/内存型号），压测机与被测机分离或至少记录压测机占用 |
| 数据量 | 同一份数据库快照（番剧 ≥ 100 条、单集弹幕 ≥ 1000 条），对照实验前重启服务并清空 Redis 缓存 |
| 部署 | 同一份 `docker-compose.yml`、同一 `JAVA_OPTS`、同一 MySQL/Redis/Mongo 容器规格 |
| 负载 | 200 / 500 / 1000 三档，每档 120 秒，ramp 10 秒 |
| 重复 | 每档跑 3 次，取中位数（排除首次 JIT/缓存冷启动） |
| 预热 | 每次正式采集前先跑 15 秒同档负载丢弃 |

### 6.2 对照项（A = 改造前，B = 改造后）

| # | 改造点 | A（基线） | B（改造后） | 预期观察指标 |
| --- | --- | --- | --- | --- |
| 1 | 弹幕落库 | 当前：`@OnMessage` 同步 `danmakuRepository.save` + Redis `rightPush` 后再广播 | RocketMQ 生产即返回，消费者批量写 Mongo，广播不等落库 | 弹幕发送 P95/错误率、Mongo 写入 QPS |
| 2 | 弹幕广播 | 当前：房间内 `CopyOnWriteArraySet<Session>` 逐 session 串行发送 | 广播任务丢线程池/本地分片，慢连接隔离 | 1000 并发下发送 P95、GC 次数 |
| 3 | 热数据读取 | 当前：番剧详情/热搜纯 Redis 或纯 MySQL | Caffeine 本地 L1 + Redis L2 | `/api/anime/{id}`、`/api/anime/hot` 的 TPS 与 P95 |
| 4 | 限流保护 | 当前：无 | Gateway Sentinel 对 `/api/danmaku/**`、`/api/search/**` 限流 + 熔断 | 超阈值时错误率是否可控（429 而非雪崩）、恢复时间 |
| 5 | 网关鉴权开销 | 当前：每次请求查 Redis 黑名单（已切 boundedElastic） | 本地短 TTL 缓存黑名单 | 网关层 P95、Redis QPS |

### 6.3 结果表模板（跑完 `run.ps1` 后用 `results/summary.md` 填）

| 场景 | 并发 | 改造 | 样本数 | TPS | 平均(ms) | P95(ms) | 错误率(%) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 弹幕发送 | 200 | A 基线 | | | | | |
| 弹幕发送 | 200 | B 异步落库 | | | | | |
| 弹幕发送 | 500 | A 基线 | | | | | |
| 弹幕发送 | 500 | B 异步落库 | | | | | |
| 弹幕发送 | 1000 | A 基线 | | | | | |
| 弹幕发送 | 1000 | B 异步落库 | | | | | |
| 番剧详情 | 100 | A 基线 | | | | | |
| 番剧详情 | 100 | B 二级缓存 | | | | | |

### 6.4 结论撰写要点（避免踩坑）

- 若 B 组提升不明显，**不要编数据**：如实写"单机内存 Mongo 下同步写耗时仅 x ms，异步收益被消息队列本身开销抵消，
  在 x 并发后才出现拐点"，这比假数据更能体现工程判断，答辩反而加分。
- 必须报告压测机瓶颈：1000 线程 WebSocket 时压测机自身可能先到瓶颈，给出 CPU 占用截图或改用分布式压测。
- 报告失败样本的具体原因（超时/拒绝连接/5xx），不要只给一个错误率数字。

## 7. 已知限制（首次运行前必读）

1. **脚本未实跑**：编写环境无 JMeter（`Get-Command jmeter` 为空），JMX 仅通过 XML 结构校验
   （元素均为合法 testclass、每个测试元素后紧跟 `hashTree`、无残留占位符）。首次运行请先跑 10 并发 30 秒。
2. **WebSocket 采样器属性名**取自插件源码（`server/port/path/requestData/createNewConnection/payloadType/optional`），
   若插件版本不同导致属性不识别，用 GUI 打开 JMX 后重新保存一次即可（JMeter 会按当前版本重写属性）。
3. **单房间模型**：所有线程进同一个房间 `animeKey:episodeNum`，这是广播扇出最坏的场景（N 个用户 → N² 条广播，
   本项目 `DanmakuWebSocketServer` 对房间内每个 session 逐个发送）。若要模拟"多房间"，需给 JMX 加
   CSV Data Set Config 让每个线程取不同 `ANIME_KEY`。
4. **弹幕读采样器设为可选**（`optional=true`）：广播是异步推送，采样器超时不应当算失败，
   因此它只贡献"接收耗时"样本，不贡献错误率。
5. **不要在生产数据上压**：压测会写入大量弹幕（每档上千条进 MongoDB），建议用独立库并压测后清理。
6. **AI 服务不在压测范围**：`/api/ai/**` 走外部 DeepSeek 接口，耗时不代表本项目性能，单独做一次 10 并发连通性验证即可。
