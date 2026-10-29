# 实验报告：弹幕写入链路的消息中间件选型与接入验证

> 论文对应章节：第 5 章「系统实现」5.x 弹幕异步化 + 第 6 章「系统测试」中的对照实验。
> 说明：本报告区分三类内容——**已实现的事实**（带 文件:行号）、**选型推理**（可答辩）、**实验方案与结果**（结果表待实跑后填，绝不编数据）。

## 1. 问题：为什么弹幕写路径需要异步化

原始实现（改造前）在 WebSocket 的 `onMessage` 里同步做三件事：MongoDB `save()` → 更新统计 → 遍历房间 `sendText()` 广播。后果：

1. 每条弹幕一次 Mongo 写，写放大严重（Mongo 单文档插入约 0.1–1 ms，但连接与 BSON 编解码开销按条计）；
2. `onMessage` 运行在 Tomcat WebSocket IO 线程上，DB 抖动会直接卡住整个房间的广播；
3. 广播是 O(房间连接数) 的同步循环，连接数上升时单条弹幕的发送耗时线性增长。

改造后的目标：**`onMessage` 恒定 O(1)**，落库与广播都移出 IO 线程。当前实现（`abc-danmaku-service`）：

```
WS onMessage ──校验/限流/去重──▶ Redis List 缓冲（按房间）
                                     │ 每 1s（fixedDelay）
                                     ▼
                       DanmakuFlushTask 批量 drain（≤500 条/房间）
                                     │
                 MQ 可用 ──RocketMQ──▶ 消费端 insertMany + 广播
                    │
                 MQ 不可用 ──▶ 本地 dispatch（insertMany + ZSET + 广播）
```

证据：`abc-danmaku-service/src/main/java/com/abc/danmakuservice/service/DanmakuFlushTask.java:19-38`（类注释里的链路图与「为什么用 fixedDelay」）、`service/DanmakuBufferService.java`（Redis List 缓冲 + `danmaku:buffer:rooms` 房间注册表，键定义见 `util/DanmakuKeys.java:12-23`）。

## 2. 选型维度

毕设场景的约束先摆清楚，选型才有意义：

| 约束 | 说明 |
| --- | --- |
| 单机部署 | 一台开发机跑 19 个常驻容器，中间件越多越吃内存（ES 堆 512 MB + Broker 512 MB） |
| 消息不丢 | 弹幕可以重复一条，但不能整批丢 |
| 顺序弱要求 | 同房间内尽量按时间顺序，跨房间无要求 |
| 吞吐 | 目标 500–1000 并发连接、峰值 2000 条/秒量级即可 |
| 生态一致 | 已用 Spring Cloud Alibaba + Nacos，希望依赖与版本由 BOM 统一管理 |
| 论文可讲 | 需要有明确的「为什么不是 Kafka/RabbitMQ」 |

## 3. 候选对比

> 下表定性结论来自各项目官方文档与社区实践（外部资料，非本系统实测）；吞吐/延迟数字仅为量级参考，答辩时不要当成本系统实测值。

| 维度 | RocketMQ 5.x | Kafka 3.x | RabbitMQ 3.x | Redis Stream |
| --- | --- | --- | --- | --- |
| 模型 | 主题 + 队列，拉取 | 分区日志，拉取 | AMQP 交换机 + 队列，推送 | 内存 Stream + 消费组 |
| 吞吐量级 | 十万级/秒 | 百万级/秒 | 万级/秒 | 十万级/秒（受内存限制） |
| 延迟 | ms 级 | ms 级（批量更大） | 亚 ms–ms | 亚 ms |
| 顺序消息 | 支持（MessageQueue 选择器） | 分区内有序 | 单队列有序 | 单 Stream 有序 |
| 延迟消息 | 支持（18 个等级） | 不支持（需外部实现） | 插件支持 | 不支持 |
| 事务消息 | 支持（半消息 + 回查） | 支持（事务 API） | 不支持 | 不支持 |
| 运维复杂度 | 中（NameServer + Broker） | 高（需 KRaft/ZK、分区规划） | 中（Erlang 运行时） | 低（就在 Redis 里） |
| 与 Spring Cloud Alibaba | 原生集成（`rocketmq-spring-boot-starter` 由 BOM 管版本） | 需自行整合 | 需自行整合 | Spring Data Redis 自带 |
| 本机内存占用 | ~512 MB–1 GB（JVM 堆可调） | ~1 GB+ | ~150 MB | 0（复用现有 Redis） |
| 许可 | Apache-2.0 | Apache-2.0 | MPL-2.0 | BSD（Redis 7 前）/ RSALv2+SSPLv1（Redis 7+） |
| 适合本项目的程度 | **高** | 中（过重） | 中 | 中（与缓冲层职责重叠） |

资料来源（外部数据，答辩可引用）：

- RocketMQ 官方文档：<https://rocketmq.apache.org/docs/>
- Kafka 官方文档：<https://kafka.apache.org/documentation/>
- RabbitMQ 官方文档：<https://www.rabbitmq.com/docs>
- Spring Cloud Alibaba 版本说明（本项目 2023.0.1.0 对齐 Spring Boot 3.2.x）：<https://github.com/alibaba/spring-cloud-alibaba/wiki/%E7%89%88%E6%9C%AC%E8%AF%B4%E6%98%8E>

## 4. 决策：RocketMQ，但做成「可关闭」

**选 RocketMQ 的三条理由：**

1. **生态一致**：`spring-cloud-alibaba-dependencies` 已统一管理 `rocketmq-spring-boot-starter` 版本，不需要自己解决版本冲突；Nacos 也是阿里系，答辩讲「技术栈收敛」有说服力。
2. **能力匹配**：本项目未来若要「定时清理弹幕 / 延迟审核」，RocketMQ 的延迟消息与事务消息是现成能力，Kafka 需要额外组件。
3. **运维可接受**：NameServer + Broker 两个容器，单机 broker 内存可压到 512 MB（`docker-compose.yml` 里 `rocketmq-broker` 段已限制 JVM 参数）。

**为什么不选另外三个：**

- Kafka：吞吐远超需求，但分区/副本规划、KRaft 模式、监控体系对毕设是纯负担（本系统峰值也就 2000 条/秒）；
- RabbitMQ：需要 Erlang 运行时，镜像更大，且 AMQP 的交换机路由模型对本场景是过度设计；
- Redis Stream：其实很合适（复用现有 Redis、有消费组与 ACK），**但本系统的写路径已经有「Redis List 缓冲 + 每秒批量」这一层**，再上 Stream 会变成「缓冲套缓冲」，职责重叠；把 Stream 作为「不引入新中间件」的备选写进论文「方案对比」即可。

**关键工程决策：MQ 是可选链路，不是硬依赖。**

| 设计 | 实现 | 好处 |
| --- | --- | --- |
| 生产者用 `ObjectProvider<RocketMQTemplate>` 注入 | `mq/DanmakuMessageProducer.java:29`、`:42-43` 的 `available()` | 没配 MQ / 没起 Broker 时应用照常启动，不抛 `NoSuchBeanDefinitionException` |
| 消费端条件装配 | `mq/DanmakuMessageConsumer.java:26` `@ConditionalOnProperty(name="danmaku.mq.enabled")` | 关闭开关时不注册监听器，不占消费线程 |
| 投递失败自动降级 | `DanmakuMessageProducer.send()` 返回 boolean（`:51-71`），`FlushTask.persist()` 为 false 时走 `dispatchLocally()`（`DanmakuFlushTask.java:124-132`） | 不丢弹幕：MQ 挂了退化为本地批量落库 |
| 开关放在配置中心可改的 yml | `abc-danmaku-service/src/main/resources/application.yml:49-55`（`danmaku.mq.enabled: ${DANMAKU_MQ_ENABLED:false}`） | 默认 false 保证本地零依赖启动；`docker-compose.yml:438` 里设 `DANMAKU_MQ_ENABLED: "true"` 演示异步链路 |

## 5. 接入实现细节（答辩可逐点展开）

### 5.1 消息模型

- Topic：`danmaku-topic`，消费组：`abc-danmaku-consumer`（`application.yml:52-53`）；
- 消息体是**批量**：一次 flush 的一条消息携带同一房间的一批弹幕（`mq/DanmakuBatchPayload.java`，字段 roomKey/animeKey/episodeNum/items），`payload-max-size: 500`（`application.yml:55`）——减少消息数，提高消费端 `insertMany` 的批处理效率；
- 使用同步发送 `syncSend`（`DanmakuMessageProducer.java:67`）：flush 线程每秒一次，3000 ms 超时（`application.yml:54`），同步能拿到发送结果才能决定「是否降级本地落库」。

### 5.2 投递语义：至少一次 + 可接受的重复

- 消费端 `onMessage` 反序列化后调用 `dispatchService.dispatch(items)`（`mq/DanmakuMessageConsumer.java:41-50`），失败抛 `IllegalStateException` 让 Broker 重投；
- 重复投递的后果是「同一批弹幕写两遍」，对弹幕业务可接受（前端按 id 去重成本高但影响小）；这是有意识的取舍，而不是没考虑幂等——论文里要写明「本场景选择 at-least-once 而非 exactly-once，因为去重表成本高于重复的观感损失」；
- 更强的幂等方案（若答辩被追问）：以 `roomKey + 消息内首条弹幕 id` 作为 Redis `SETNX` 幂等键，TTL 10 分钟；当前未实现，属于「可扩展点」。

### 5.3 落库与广播的顺序

`service/DanmakuDispatchService.java` 的四步顺序刻意设计为：**先落库 → 再建 ZSET 索引 → 缓存失效 → 广播**。理由（源码注释原文）：广播是「尽力而为」，历史查询是「必须能查到」，落库失败就不该广播。

这条顺序让「MQ 开启」与「MQ 关闭」两条路径的行为完全一致，对照实验才有可比性。

### 5.4 积压与可观测性

`DanmakuFlushTask` 用 6 个 `AtomicLong` 计数器（`:50-57`）暴露 `flushRounds / drainedCount / savedCount / droppedCount / mqSentBatches / mqFailedBatches / mongoFallbackBatches / lastFlushCostMs`，并每轮打一行 INFO（`:113`）：

```
弹幕 flush 完成: rooms=3 drained=120 saved=120 cost=12ms mqSent=3 mqFailed=0 fallback=0
```

缓冲积压超过 `danmaku.buffer.warn-size`（默认 5000）会打 WARN（`application.yml:30`），这就是压测时判断「是否削峰成功」的直接观测点。

## 6. 对照实验设计

### 6.1 实验假设

- H1：开启 MQ 后，WS 侧 `onMessage` 的 P95 与吞吐提升（因为落库彻底离开请求线程）；
- H2：单机同进程部署时，本地批量落库（MQ 关闭）其实已经很快，MQ 的收益主要体现在**积压削峰**与**可横向扩展**（多实例消费），而不是单条延迟；
- H3：MQ 开启时若 Broker 抖动，`mqFailed` 上升、`fallback` 上升，系统整体仍可用（降级生效）。

### 6.2 控制变量

| 项 | 设置 |
| --- | --- |
| 环境 | 同一台机器、同一份代码、同一份 compose，仅切换 `DANMAKU_MQ_ENABLED` |
| JVM | 同一 `JAVA_OPTS`（建议 `-Xms512m -Xmx512m`） |
| 数据 | 固定房间 `bgm:10380` + 第 1 集，压测前清空该房间缓冲与集合 |
| 负载 | 200 / 500 / 1000 并发 WS 连接，每连接每 300 ms 发 1 条，持续 120 s，ramp-up 10 s |
| 重复 | 每档跑 3 次取中位数，先跑 15 s 预热并丢弃 |
| 监控 | `docker stats`（CPU/内存）、flush INFO 日志、MongoDB 集合文档数 |

### 6.3 执行命令

```powershell
# MQ 关闭组：把 docker-compose.yml 中 DANMAKU_MQ_ENABLED 改为 "false" 或直接不设（默认 false）
docker compose up -d abc-danmaku-service

# 压测（脚本见 jmeter/danmaku-test.jmx 与 jmeter/run.ps1）
.\jmeter\run.ps1 -Scenario danmaku -Levels 200,500,1000 -Duration 120 -Ramp 10
```

MongoDB 侧核验（落库条数必须与压测发送条数一致，误差只允许来自限流/去重拦截）：

```powershell
docker exec abc-mongodb mongosh -u root -p mongo123 --authenticationDatabase admin abc_danmaku --eval "db.danmaku.countDocuments({})"
```

### 6.4 记录表模板

| 场景 | 并发 | TPS（发送） | 发送 P95/ms | 发送错误率 | flush cost/ms | mqSent | mqFailed | fallback | Mongo 条数 | 服务 CPU |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MQ 关闭 | 200 |  |  |  |  | 0 | 0 |  |  |  |
| MQ 关闭 | 500 |  |  |  |  | 0 | 0 |  |  |  |
| MQ 关闭 | 1000 |  |  |  |  | 0 | 0 |  |  |  |
| MQ 开启 | 200 |  |  |  |  |  |  |  |  |  |
| MQ 开启 | 500 |  |  |  |  |  |  |  |  |  |
| MQ 开启 | 1000 |  |  |  |  |  |  |  |  |  |

> 表格留白是刻意的：**不要填没跑过的数字**。答辩时如果被问「你测出提升多少」，如实回答「本机单机部署下差距在误差范围内，MQ 的收益体现在多实例消费与削峰，压测数据见附录」，反而更可信。

### 6.5 判定标准

- 若 TPS(开启) ≥ TPS(关闭) × 1.2 或 P95(开启) ≤ P95(关闭) × 0.8 → 支持 H1，可写「异步化带来 x% 提升」；
- 若两者接近 → 支持 H2，可写「单机场景下批量本地落库已接近瓶颈，MQ 主要价值在解耦与扩展」，并补一个「两个弹幕实例同时消费」的实验来证明扩展性；
- 若 Mongo 条数 < 发送条数 → 先查限流/去重计数（预期被拦截数），再查 `mqFailed` 与 `dropped`，不能直接写成「丢了」。

## 7. 风险与未验证项（必须写进论文「不足与展望」）

| 项 | 现状 | 风险 |
| --- | --- | --- |
| 脚本未实跑 | `jmeter/*.jmx` 已用自写校验器检查 XML 结构，但未在安装 JMeter 的机器上真跑过 | 采样器属性名可能与插件版本不符，首次跑要留出调试时间 |
| 未做多实例压测 | 单实例 + 单 Broker | 「MQ 提升扩展性」只有推理，缺实测 |
| 未实现严格幂等 | at-least-once | 极端情况重复弹幕 |
| Broker 单机 | `rocketmq-broker` 单节点、无副本 | 节点挂掉期间只能降级本地落库 |
| 消息体未压缩 | JSON 明文 | 大批量时网络开销偏高（当前批 ≤500 条可接受） |

## 8. 结论（可直接改写进论文）

本节针对弹幕高并发写入导致的 WebSocket 线程阻塞问题，设计了「Redis 缓冲 + 定时批量落库 + 可选 RocketMQ 异步」的三级写路径。
通过 `ObjectProvider` 注入与 `@ConditionalOnProperty` 条件装配把消息中间件做成可插拔依赖，使系统在无 MQ 环境下仍可完整运行并自动降级为本地批量落库，兼顾了「毕设可运行性」与「生产可扩展性」。
选型上比较了 Kafka、RabbitMQ、Redis Stream 与 RocketMQ，最终基于生态一致性（Spring Cloud Alibaba BOM 统一管理）、延迟消息等能力与单机运维成本选择 RocketMQ。
对照实验方法见 §6；实测数据与结论在论文定稿前由 `jmeter/run.ps1` 生成并回填。
