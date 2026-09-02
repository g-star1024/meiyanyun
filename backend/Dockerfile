# 后端微服务运行镜像
# 策略：复制「宿主已构建」的 fat-jar，避免镜像内慢构建（mvn package 已在宿主完成）。
# 通过 build args 指定 SERVICE（服务目录名）与 PORT（监听端口）。
# 这样 7 个服务共用同一份 Dockerfile，compose 中按服务传参即可。
FROM eclipse-temurin:17-jre

WORKDIR /app

ARG SERVICE
ARG PORT=8082

COPY backend/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE ${PORT}

# JVM 内存上限，避免 7 个实例同时拉起把宿主内存打爆。
# JAVA_TOOL_OPTIONS 会被 JVM 自动读取，无需改 ENTRYPOINT。
ENV JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m -XX:+UseG1GC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
