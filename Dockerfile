# ---------- 构建阶段 ----------
# 用带 Maven 的 JDK 镜像构建：构建环境不依赖本机装了什么，CI 上也能复用
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只拷 pom 单独拉依赖：源码改动时这一层能命中缓存，
# 不用每次都重新下载全部依赖（这是多阶段构建里最值得做的一步优化）
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
# openjdk:17-jdk-slim 已停止维护，换成仍在更新的 Temurin JRE 镜像；
# 运行只需要 JRE，不必带 JDK
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 容器默认时区是 UTC，日志时间会比本地时间差 8 小时，排查问题时很干扰
ENV TZ=Asia/Shanghai

COPY --from=build /build/target/WeeklyReportManager-*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage：让 JVM 按 cgroup 限制（也就是 compose 里给的 mem_limit）计算堆大小。
# 不加的话 JVM 可能按宿主机总内存来算，容器内存受限时容易被 OOM Kill。
# 这里没有加 --spring.profiles.active，环境相关的配置全部由 compose 的环境变量覆盖。
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Duser.timezone=Asia/Shanghai", \
    "-jar", "app.jar"]
