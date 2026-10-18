FROM eclipse-temurin:21-jre
WORKDIR /app

# curl 只给 docker healthcheck 用（健康检查在容器内执行，这个基础镜像里没有 curl）。
# 它不参与业务；装完顺手删掉 apt 缓存，别让镜像白白变大。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

EXPOSE 8080
# 堆外内存、代理、容器及系统缓存仍需额外内存；此堆上限不是两核4G容量保证。
# 实际并发、内存峰值与重启时间需要部署环境验收。
# JVM发生OutOfMemoryError时退出，由容器重启策略恢复进程。
ENTRYPOINT ["java", "-Xmx2500m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
