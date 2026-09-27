FROM eclipse-temurin:21-jre
WORKDIR /app

# curl 只给 docker healthcheck 用（健康检查在容器内执行，这个基础镜像里没有 curl）。
# 它不参与业务；装完顺手删掉 apt 缓存，别让镜像白白变大。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

EXPOSE 8080
# 堆上限 2.5G，不是 3G：这台机器是 2核4G，系统 + nginx + docker 自己要吃掉 400~600MB，
# JVM 的非堆部分（元空间、CodeCache、直接内存、GC 结构）还要 300~500MB。
# 3G 堆 + 这些 = 顶到 4G 红线，一旦超了会被 Linux OOM killer 直接杀进程 ——
# 现象是"应用莫名其妙重启一遍"，日志里往往什么都没有（exit code 137）。
# 2.5G 留出安全水位，且离真实峰值（几十个并发轮次）仍然很远。
# -XX:+ExitOnOutOfMemoryError：真撞上 OOM 时快速退出让 docker 重启（~20 秒），
# 而不是留一个"还活着但每个线程都在抛 OOM"的半死进程 —— 后者只能靠人工发现并重启。
ENTRYPOINT ["java", "-Xmx2500m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
