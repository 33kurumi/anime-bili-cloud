# Nacos 配置中心 — 使用说明

本项目用 **Nacos** 做两件事：

| 身份 | 作用 | 状态 |
|---|---|---|
| 注册中心 (discovery) | 服务互相发现（`lb://abc-xxx-service`） | 原有 |
| **配置中心 (config)** | **集中存公共配置，各服务启动时拉取** | **本次新增** |

## 为什么用配置中心

原先把 MySQL/Redis 连接信息抄在 9 个 `application.yml` 里，改一次要改 9 遍。
现在**公共配置只在 Nacos 存一份**，各服务统一拉取：

```
Nacos: abc-common.yaml       ← Redis 连接（改这里，所有服务全生效）
Nacos: abc-datasource.yaml   ← MySQL 连接
   ▲
   │ shared-configs（bootstrap 引导阶段加载）
   │
各服务 application.yml 只留自己的差异项（端口、redis database、mybatis、业务配置）
```

## Data ID / Group 约定

| Data ID | Group | 内容 | 谁导入 |
|---|---|---|---|
| `abc-common.yaml` | `DEFAULT_GROUP` | Redis 连接 | 8 个服务（除 gateway） |
| `abc-datasource.yaml` | `DEFAULT_GROUP` | MySQL 连接 | 7 个有数据库的服务 |

> danmaku-service 用 MongoDB，**不导入** datasource（避免无 mysql 驱动报错）。

## 一键导入

```bash
bash sql/nacos-config.sh
```
脚本通过 Nacos Open API 发布配置（`sql/nacos/` 下的 yaml 源文件）。

## 接入方式（实际生效的写法）

Spring Cloud Alibaba **2023.0.1.0** 下，`spring.config.import: nacos:xxx` 方式存在兼容问题
（配置含中文注释会解析失败），**采用经典的 bootstrap + shared-configs 方式**：

每服务加依赖：
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

**bootstrap.yml**（引导阶段）：
```yaml
spring:
  application:
    name: abc-xxx-service
  cloud:
    nacos:
      username: nacos
      password: nacos
      config:
        server-addr: 127.0.0.1:8848
        username: nacos      # ← 关键！config 段必须带账号，否则 403 user not found
        password: nacos
        file-extension: yaml
        shared-configs:
          - data-id: abc-common.yaml
            refresh: true
          - data-id: abc-datasource.yaml
            refresh: true
      discovery:
        server-addr: 127.0.0.1:8848
        username: nacos
        password: nacos
```

**application.yml**（只留差异项）：
```yaml
spring:
  data:
    redis:
      database: 1     # host/port/password 来自 Nacos
server:
  port: 8083
```

## ⚠ 踩坑记录（重要）

1. **Nacos 鉴权**：`docker-compose.yml` 保持 `NACOS_AUTH_ENABLE=true`（控制台用 `nacos/nacos` 登录）。
   关键：各服务 `bootstrap.yml` 里 **`spring.cloud.nacos.config` 下必须带 `username/password`**。
   若只在 `discovery` 下写、`config` 下漏写 → 配置客户端裸奔 → Nacos 返回
   `403 user not found!`，看起来像"配置不存在"。**鉴权可以开，别被这个报错误导去关鉴权。**
2. **中文注释**：发布到 Nacos 的 yaml **务必用英文/ASCII 注释**。含中文注释时，
   curl 发布的中文会被错误编码 → 客户端 `parse data from Nacos error`（YAML 解析失败）。
3. 配置生效验证：启动日志出现
   `Located property source: [..., abc-datasource.yaml, abc-common.yaml]` 即成功。

## 动态刷新（可选）

`shared-configs` 配了 `refresh: true`；对需要热更新的 Bean 加 `@RefreshScope`，
改 Nacos 后调 `POST /actuator/refresh`（需引入 actuator）即可不重启生效。
