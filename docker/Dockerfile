# HireHub 服务通用镜像：直接跑 `mvn package` 产出的 Spring Boot fat jar
#
# 构建（在仓库根目录执行，用 --build-arg 指定模块）：
#   docker build -f docker/Dockerfile --build-arg JAR=hirehub-auth -t hirehub-auth .
#
# 为什么不做 multi-stage 构建：本项目的构建在宿主/CI 上用 Maven 完成，
# 镜像只负责「跑起来」，这样镜像层小、构建快，也便于和 IDE 开发共用同一份产物。
FROM eclipse-temurin:17-jre

ARG JAR
ENV TZ=Asia/Shanghai

COPY ${JAR}/target/${JAR}-1.0.0.jar /app.jar

# 容器内不做堆上限硬编码，用 MaxRAMPercentage 跟随 mem_limit 自适应
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=Asia/Shanghai", "-jar", "/app.jar"]
