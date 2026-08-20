# 1. 使用轻量级 JDK 17 镜像
FROM openjdk:17-jdk-slim

# 2. 设置容器内部工作目录
WORKDIR /app

# 3. 把本地打包好的 jar 包复制到容器里
COPY target/WeeklyReportManager-*.jar app.jar

# 4. 暴露 8080 端口
EXPOSE 8080

# 5. 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]