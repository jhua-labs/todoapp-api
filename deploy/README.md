# todoapp staging 部署说明（AD-15，Story 4.2 / OPS-1）

本目录用 Docker Compose 在 staging 单机上构建并运行三个服务：`db`（PostgreSQL 18.6）、`api`（本 repo）、`web`（同级 `todoapp-web`，nginx 托管前端并把 `/api` 反代到 `api:8080`）。

## 安全边界：只能部署在内网（不可协商）

本服务**没有登录、没有鉴权**，只能部署在公司内网（或等效的无公网入站环境）。绝对禁止通过任何方式让它在内网边界之外以无鉴权可达的形态暴露，包括但不限于：反向代理、端口转发、NAT、对外 VPN/跳板、隧道。唯一受控的例外见下一节，且仅限 staging。

部署文件保证的暴露面：

- 只有 `web` 发布端口，且只绑定本机回环 `127.0.0.1:8080:80`；`api`、`db` 不发布任何端口，只在 compose 默认网络里互通。
- 不得给 `compose.yaml` 增加服务、端口映射或 `network_mode`，不得把 `127.0.0.1` 改成其他地址或省略（省略会绑定所有网卡）。

实例层必须同时满足（本 repo 没有 IaC，部署后人工核验）：

- 实例不分配公网 IP，不挂 ALB/EIP；
- 安全组零入站规则；
- 不部署任何其他让服务可达的组件（反向代理、NAT、对外 VPN/跳板、隧道等一律禁止）。

## 唯一例外：SSM 端口转发（仅限 staging）

staging 允许经 AWS Systems Manager 端口转发访问，**三条必须同时成立，缺一条例外即失效**：

1. **只有持有 SSM 权限的 IAM 主体能访问**：`ssm:StartSession` 只授予指定的 IAM 主体。
2. **实例没有公网入站**：不分配公网 IP、安全组零入站、不挂 ALB/EIP。
3. **只转发到实例本机 `127.0.0.1:8080`**：只用 `AWS-StartPortForwardingSession` 文档，不转发到其他主机，不用远程主机转发；本地端口固定 `localPortNumber=18080`（避开本地开发用的 8080）。

示例（实例 ID 用占位符）：

```bash
aws ssm start-session \
  --target <instance-id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}'
```

隧道建好后在本机访问 `http://127.0.0.1:18080`。

此例外**不适用于正式环境**。staging 只放测试数据，不是正式环境。staging 不做备份，RPO=0 尚未生效；上正式环境前必须先落实备份（OQ-6）。

## 凭据

- 首次部署：在 `todoapp-api/deploy/` 下 `cp .env.example .env`，填写 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`。
- `deploy/.env` 已被 `todoapp-api/.gitignore` 忽略，**不提交**；仓库里只有 `.env.example`（只含占位）。
- `api` 通过 `SPRING_DATASOURCE_*` 环境变量和 `staging` profile 读取数据源，镜像和 yaml 里都不写密码。

## 部署流程

1. 在服务器上把同级目录 `todoapp-api/`、`todoapp-web/` 都 `git pull` 到目标 commit（compose 的 `web` 构建上下文是 `../../todoapp-web`，两个目录必须并列）。
2. 首次部署先按上一节准备 `deploy/.env`；然后在 `todoapp-api/deploy/` 执行 `docker compose up -d --build`（在 ARM 主机上原生构建）。
3. 冒烟：在实例上执行 `curl -fsS 127.0.0.1:8080/api/todos`，返回 200 和 `{"items":[…]}` 即成功。`web` 只等 `api` 容器启动、不等它就绪，刚启动的几秒里 `/api` 可能返回 500 契约错误体，稍候重试即可。
4. 迁移失败（`api` 启动时 Flyway 报错退出）：把代码回退到上一个 commit 重新部署，再用新增迁移（`V{n}__…sql`，并先改 `ddl.sql`）修复。已执行的迁移不可变（AD-5），不要改已有的迁移文件。

## 部署后核验（人工，staging）

- 暴露面：在实例上 `ss -ltnp`，只看到 `127.0.0.1:8080`（docker-proxy），没有 `0.0.0.0:8080`、`:5432`；`docker compose ps` 只有 `web` 有端口映射。
- SSM 三条件逐条核对：IAM 授权范围、实例无公网 IP / 安全组零入站 / 无 ALB/EIP、只用 `AWS-StartPortForwardingSession` 转发到 `127.0.0.1:8080`。
- 经 SSM 隧道（本地 18080）在工作区根运行 staging 测试：`todoapp-api/mvnw -f todoapp-api/pom.xml test -Dgroups=needs-staging`。
- 可选：上游不可用改写。`docker compose stop api` 后 `curl -s 127.0.0.1:8080/api/todos` 应返回 500 和 `{"error":{"code":"INTERNAL_ERROR",…}}`，确认后 `docker compose start api`。会短暂中断 staging，按需执行。
