# syntax=docker/dockerfile:1
# ============================================================
# abc-ai-service 镜像（多阶段构建）—— AI 桌宠(RAG)
#   阶段 1 build  : 用 JDK17 + Maven 编译打包（-pl 只构建本模块，-am 连带 abc-common）
#   阶段 2 runtime: 只带 JRE 的运行镜像，体积更小、攻击面更小
# 构建上下文 = 项目根目录（需要根 pom.xml 与 abc-common 一起进构建），见根目录 .dockerignore
#   docker build -f abc-ai-service/Dockerfile -t abc-ai-service:1.0.0 .
# 说明：依赖缓存用 BuildKit cache mount 挂在 /root/.m2，
#       10 个业务镜像共用同一份 Maven 依赖，第二次构建不再联网下载。
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true -pl abc-ai-service -am package

FROM eclipse-temurin:17-jre
# 时区/编码统一：日志与 DATETIME、JWT 过期时间都按北京时间计算，中文日志不乱码
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
WORKDIR /app
# 非 root 运行：容器被突破时拿不到 root 权限（安全基线）
RUN groupadd -r abc && useradd -r -g abc abc
COPY --from=build /build/abc-ai-service/target/*.jar /app/app.jar
RUN chown -R abc:abc /app
USER abc
EXPOSE 8089
# 用 sh -c 才能展开 $JAVA_OPTS；exec 让 java 成为 PID 1，可正确收到 SIGTERM 优雅停机
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
