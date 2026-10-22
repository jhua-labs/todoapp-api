# todoapp-api 镜像（AD-15，Story 4.2 / OPS-1）：多阶段构建，Maven 构建可执行 jar，JRE 运行。
# 由 deploy/compose.yaml 的 api 服务构建（context: ..）；staging profile 与数据源由 compose 环境变量提供。
# 这里不写任何数据源地址、用户名或密码。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 只拷构建需要的文件（不 COPY . .，避免把 .local-pg/、target/、deploy/.env 带进构建层）
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
# 用镜像里的 mvn（AD-15）；-DskipTests 仍会编译测试，test 范围依赖只下载不执行
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /build/target/todoapp-api-0.0.1-SNAPSHOT.jar /app/app.jar
# 只是元数据，不发布端口；对外暴露面只由 deploy/compose.yaml 决定（api 不发布任何端口）
EXPOSE 8080
# exec 形式：docker stop 的 SIGTERM 直达 JVM，Spring 优雅关闭
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
