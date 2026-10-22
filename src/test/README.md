# todoapp-api 测试框架

后端测试栈由架构 AD-16 固定：JUnit Jupiter 6.0.3 + `@SpringBootTest` + MockMvc + embedded PostgreSQL 18.6（zonky 2.2.2）。
用例清单与优先级见 `_bmad-output/test-artifacts/test-design/test-design-qa.md`（规划 repo）。

## 运行

命令都在工作区根执行（AD-16）。

> **前提：`todoapp-web` 必须和 `todoapp-api` 并排 checkout 在同一个工作区根目录下。**`deploy/DeployArtifactsTest`（Story 4.2）会读取 `../todoapp-web/Dockerfile`，用来核对 web 镜像的多阶段构建；找不到这个文件时，测试会**直接失败**，不会跳过。部署时本来就要求两个仓库放在同级目录（AD-15，compose 的 `web.build.context: ../../todoapp-web`）。只 clone 了 api 的人或者 CI，要先把 web 也 checkout 到旁边。（Epic 4 检查点，PM 2026-10-20 选 A）

| 目的 | 命令 |
| --- | --- |
| 全量（PR / 本地，默认排除 `needs-staging`） | `todoapp-api/mvnw -f todoapp-api/pom.xml test` |
| 同上，显式写出排除（测试设计里的回归命令） | `todoapp-api/mvnw -f todoapp-api/pom.xml test -DexcludedGroups=needs-staging` |
| 单个类 / 方法 | `todoapp-api/mvnw -f todoapp-api/pom.xml test -Dtest=IntegrationFrameworkTest` 或 `-Dtest='IntegrationFrameworkTest#activatesNoProfile'` |
| 只跑 staging 测试（先建好 SSM 隧道，本地 18080） | `todoapp-api/mvnw -f todoapp-api/pom.xml test -Dgroups=needs-staging` |
| staging 测试只检查能编译 | `todoapp-api/mvnw -f todoapp-api/pom.xml test-compile` |

- 环境：JDK 21（`.java-version`；enforcer 强制 `[21,22)`）。wrapper 固定 Maven 3.9.16（带 sha256 校验），首次运行会下载；直接用 `mvn` 也行，但不得低于 3.9.16。
- 不需要 Docker，也不需要本机装 PostgreSQL：embedded PG 二进制由 Maven 依赖带入（darwin-arm64 已显式引入）。
- 报告：`todoapp-api/target/surefire-reports/`（JUnit XML + txt，CI 直接收集）。
- staging 地址只从 `STAGING_BASE_URL` 读（环境变量，或 `-DSTAGING_BASE_URL=...`），默认 `http://127.0.0.1:18080`。

## 结构

```text
src/test/java/com/todoapp/
  support/   测试基座（BE-0 拥有，AD-17）
    IntegrationTest              组合注解：集成测试唯一入口
    TestDatabase                 embedded PG 18.6 单例：随机端口（避开 54329/5432）+ 临时目录 + UTF8
    TestEnvironmentInitializer   spring.datasource.* 指向测试库（Hikari / Flyway 走正常自动配置）；server.address=127.0.0.1
    TestInfrastructureConfig     @Primary MutableClock、DatabaseCleaner
    ResetTestStateListener       每个测试前清表 + 复位时钟（先于 @Sql 播种和测试事务）
    MutableClock                 冻结时钟（可 set / advance / 回拨）
    DatabaseCleaner              TRUNCATE public 下除 flyway_schema_history 外的全部表；只清本次启动的测试库
    TestTags                     needs-staging 标签常量
    StagingBoundaryTest          守住 staging 唯一入口（本地运行）
    IntegrationFrameworkTest     INF-B2：基座自检（MOCK web 环境）
    RandomPortFrameworkTest      INF-B2：RANDOM_PORT 形态自检、只监听回环
    MutableClockTest             纯单元测试示例
    TodoSeeder                   INF-B3：直接 SQL 批量播种 todo（纯静态工具，非 bean；created_at = base + i ms，时间须已截断到毫秒）
    TodoSeederTest               INF-B3：播种 helper 自检（500 条、严格递增、满足约束）
  staging/   StagingTestBase（staging 唯一入口）+ 继承它的 staging 测试（只在 staging 上跑）
    StagingDeploymentTest  Story 4.2 OPS-5 冒烟 + nginx 边界（needs-staging）
  todo/      BE-1..BE-4 的测试（Story 2.1 已创建，2.2→3.1→3.2 串行追加）
    TodoSchemaTest               AC-1 / BE-G4：V1 建表逐项对照 ddl.sql、约束按名生效、Flyway 设置不放宽
    SchemaDriftTest              AC-2 / BE-S1：Flyway 与 ddl.sql 两个空库的 pg_catalog 目录快照比对（不依赖 pg_dump，见「排错」）
    TodoListApiTest              AC-3/4/6 / BE-G1/G2/G3/E1：GET /api/todos 排序、线上格式、500 条、时区、空表、未知子路径
    TodoLayeringTest             AC-5 / BE-S2：分层与 TodoService.now() 截断（纯反射单元测试）
    TitleRulesTest               Story 2.2 AC-2 / BE-P2..P5：TitleRules 跑 title-cases.json 17 条 + 裁剪集合防漂移（纯单元）
    TodoCreateApiTest            Story 2.2 AC-1/2/3/5 / BE-P1..P6、P9、P10、G4、X1：POST 201 置顶、max+1ms、标题规则、满 500 → 409、严格校验
    TodoCapacityConcurrencyTest  Story 2.2 AC-4 / BE-P7、BE-P8：RANDOM_PORT 真并发 20 个 POST 恰 1 个 201；另一连接占锁 → 500 且 < 5s
    TodoCreateLayeringTest       Story 2.2 AC-6 / BE-S2：create 单事务入口、POST 映射、TitleRules 静态、锁常量键唯一（纯反射）
    TodoIdsTest                  Story 3.1 BE-U8：TodoIds.parse 只认小写规范 UUID，其余形态（大写、不足位、去连字符、null…）→ NotFoundException（纯单元）
    TodoUpdateApiTest            Story 3.1 AC-1..6 / BE-U1..U9、BE-X1：PATCH 切换完成、幂等不更新 updatedAt、改标题裁剪、400 请求体三态、非法 id → 404、时钟回拨 GREATEST
    TodoUpdateLayeringTest       Story 3.1 BE-S2：update 单事务入口、PATCH 映射 (String, ObjectNode)、无 UUID 参数、TodoIds 静态、无 PATCH DTO（纯反射）
    TodoDeleteApiTest            Story 3.2 AC-1..4 / BE-D1、BE-D2、BE-P10、BE-E1：DELETE 204 无体、再删 404、非法 / 不存在 id → 404、满 500 删一条可再新增、不检查也不记录请求体
    TodoDeleteLayeringTest       Story 3.2 BE-S2：delete 单事务入口、DELETE 映射 (String) 无请求体 / consumes / produces、repository 返回 int、无 DELETE DTO（纯反射）
  local/     LocalDevApplication / LocalDbConfig（AD-14，BE-0）+ LocalProfileIsolationTest
  devscript/  DevScriptTest  Story 4.1 DEV-1 dev.sh 静态断言
  deploy/  DeployArtifactsTest  Story 4.2 OPS-1/OPS-2 compose、Dockerfile、README 静态断言
  common/error/  统一错误体测试（BE-E1/BE-E2）+ 测试探针 ErrorProbeController（@TestComponent，只经 @Import 引入）
  config/    Clock bean 与主配置测试
```

## 本地开发入口（AD-14，不是测试）

```bash
todoapp-api/mvnw -f todoapp-api/pom.xml spring-boot:test-run
```

激活 `local` profile；embedded PG 固定端口 54329，数据目录 `todoapp-api/.local-pg/` 持久保留；后端监听 `127.0.0.1:8080`。用 Ctrl-C / SIGTERM 停，不要 `kill -9`。brew 退路加 `-Dspring-boot.run.arguments=--todoapp.local-db.embedded=false`。

一键起全栈（Story 4.1 / DEV-1）：在工作区根执行

```bash
todoapp-api/scripts/dev.sh
```

先检查 Node 主版本为 24（不符直接报错退出），再并行起后端（上面的 `spring-boot:test-run`）与前端 `npm run dev`（不启用 MSW）。前端 `http://127.0.0.1:5173`，`/api` 经 Vite 代理到 `127.0.0.1:8080`。Ctrl-C（或对脚本发 SIGTERM）用 SIGTERM 一起停两者；任一方自己退出（如端口被占）时另一方也被停掉、脚本非 0 退出。brew 退路：`TODOAPP_LOCAL_DB_EMBEDDED=false todoapp-api/scripts/dev.sh`。

测试侧会产生 bean 的类（`TestInfrastructureConfig`、测试探针 controller 等）一律加 `@Profile("!local")`，否则 test-run 会把冻结时钟或探针接口扫进本地运行；`LocalProfileIsolationTest` 守住这一条。测试里绝不启动 `LocalDbConfig`、绝不连 54329。

## 写集成测试

```java
@IntegrationTest
@DisplayName("BE-G1 GET /api/todos")
class TodoListApiTest {

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;

    @Test
    @DisplayName("按 createdAt 倒序返回")
    void listsNewestFirst() throws Exception {
        // Given：用 @Sql / JdbcClient / API 播种；需要不同时间就 clock.advance(...)
        // When / Then：mvc.perform(get("/api/todos")).andExpect(...)
    }
}
```

`@IntegrationTest` 保证：

- embedded PG 18.6，随机端口，绝不是 54329（本地开发库）或 5432（brew）；JVM 内只启动一次，所有测试类共用。清表前向服务端核对端口，只认本次启动的测试库。
- 不激活任何 profile（尤其不是 `local`）。
- 每个测试**开始前**清空业务表、`MutableClock` 复位到 `MutableClock.DEFAULT_INSTANT`（`2026-01-15T02:30:00.123456Z`，故意带微秒，用来证明截断发生在 `TodoService.now()`）。这一步排在 `@Sql` 播种和 `@Transactional` 测试事务之前，播种的数据不会被清掉。
- 注入点拿到的 `Clock` 就是 `MutableClock`（`@Primary`，生产侧有 `Clock` bean 之后依然成立，`IntegrationFrameworkTest` 会守住这一条）。
- 需要真实 HTTP 端口时（BE-2 并发测试），在测试类上再加 `@SpringBootTest(webEnvironment = RANDOM_PORT)`，其余基座照常生效，Tomcat 只监听 `127.0.0.1`（`RandomPortFrameworkTest` 守住）。并发测试不要加 `@Transactional`：测试事务会把请求串行化，测不出 AD-10 的竞争。

纯逻辑（如 `TitleRules`）写普通 JUnit 单元测试，不加 `@IntegrationTest`，参照 `MutableClockTest`。

## needs-staging 标签约定

- 只有部署后才能证明的断言（安全边界运行时、SSM 三条件、性能、冒烟）写在 `com.todoapp.staging` 包里，继承 `StagingTestBase`：
  - 类级别 `@Tag("needs-staging")` 由基类带上（`@Tag` 可继承），子类不用再写；
  - 不用 `@SpringBootTest`，用基类的 `get` / `postJson` / `patchJson` / `delete` / `request` + `send` / `json` 打已部署的服务；
  - 其他测试不得读取 `STAGING_BASE_URL`、不得引用 `StagingTestBase`、不得自己打 needs-staging 标签（`StagingBoundaryTest` 守住）。
- `pom.xml` 默认 `excludedGroups=needs-staging`，所以裸跑 `mvnw test` 与带 `-DexcludedGroups=needs-staging` 结果一致。
- 传 `-Dgroups=...` 会自动激活 `staging-tests` profile：清空默认排除，并在选不到任何测试时让构建失败（不会出现「0 个测试、BUILD SUCCESS」的假绿）。
- 这组测试**不计入开发期覆盖门槛**（测试设计「标签约定」）。
- 被排除的 staging 测试仍参与编译，坏了 `test-compile` 会发现；不要用 `@Disabled` 代替标签。

## 测试纪律

摘自 `test-quality.md` 与测试设计 Appendix A：

- **确定性**：时间只来自 `MutableClock`；禁止 `Thread.sleep`、禁止读系统时钟做断言。surefire 以 `user.timezone=Asia/Shanghai` 运行，代码误用系统时区会直接暴露。
- **隔离**：依赖基座的「测试前清表」，不依赖其他测试留下的数据，不依赖执行顺序（`IntegrationFrameworkTest` 里清表自检那一对是唯一例外，用来证明监听器确实挂上了）。
- **顺序执行**：整套共用一个库并在每个测试前清表，`junit-platform.properties` 显式关闭并行，不要打开。
- **断言能失败**：断值不断形；禁止 `assertThat(true).isTrue()`、只断 mock 被调用、只断 `instanceof String`。
- **禁止**提交 `@Disabled` 而不写可执行的理由；只能在 staging 上证明的改为继承 `StagingTestBase`，否则删掉。
- **体量**：单个测试 ≤ 1.5 分钟（默认超时 90s；阻塞在 JDBC / socket 上的情况靠显式超时兜住，见 `junit-platform.properties` 注释），单个文件 ≤ 1000 行。
- **命名**：类和方法用 `@DisplayName` 写中文描述，带测试设计里的 Test ID（如 `BE-P7`）。
- **文本排序**：测试库用 `locale=C`，staging 是 en_US.utf8，业务代码和断言都不得依赖文本排序规则。

## 构建守卫（INF-B1）

`maven-enforcer-plugin` 在 `validate` 阶段检查：JDK 为 21；Maven ≥ 3.9.16；依赖树里没有 JPA / Hibernate / jakarta.persistence / MyBatis / jOOQ（AD-3）。违反时构建直接失败。

## 后续 story 要接上的部分

| 内容 | 归属 |
| --- | --- |
| `scripts/dev.sh` 一键起全栈 | DEV-1 |

## 排错

| 现象 | 处理 |
| --- | --- |
| enforcer 报 `RequireJavaVersion` | `JAVA_HOME` 没指向 JDK 21 |
| embedded PG 启动失败、找不到二进制 | 换了平台（如 linux-arm64）需在 `pom.xml` 加对应的 `embedded-postgres-binaries-*`（版本由 BOM 固定 18.6.0） |
| embedded PG 启动超时 | 检查 `/tmp` 可写；有残留 postgres 进程时 `ps aux \| grep embedded-pg` 后正常结束它 |
| `拒绝清表：当前连接的库端口是 ...` | 有人把 `spring.datasource.url` 指向了别的库，测试只能连基座启动的 embedded PG |
| `清表失败（可能有上一个测试遗留的未结束事务持有锁）` | 上一个测试开了事务没关（多见于手工持锁的测试），检查它的 finally |
| `No tests were executed!` | `-Dgroups=` 写的标签没有任何测试，检查拼写 |
| staging 测试 `ConnectException` | SSM 端口转发没建好，或本地端口不是 18080 |
| `SchemaDriftTest` 报 schema 快照差异 | schema 比对**不依赖 pg_dump**（zonky 18.6.0 二进制不带它；Story 2.1 决策闸 2026-10-20 PM 选目录查询方案）：在测试 embedded PG 上建两个临时库，分别跑 Flyway 与 `ddl.sql`，比对 `pg_catalog` 快照（关系、列、约束、索引、扩展）。失败信息逐项列出「只在左侧/只在右侧/不同」的对象；左 = Flyway，右 = `ddl.sql`。按 AD-5 先改 `ddl.sql` 再新增 Vn，不要改已执行的迁移 |

## 写时拦截（未安装）

TEA 的写时拦截 hook（`.claude/hooks/tea-enforce.cjs`）面向 Claude Code，且要装在工作区根；本次框架文件只放在 `todoapp-api/`，没有安装。测试质量规则由 `bmad-testarch-test-review` 事后检查。

## CI

暂不建 CI（架构 Deferred）。将来接入时 PR 阶段跑全量命令即可（默认已排除 `needs-staging`），收集 `target/surefire-reports/*.xml`。

## 知识库参考

`test-quality.md`、`fixture-architecture.md`（组合优于继承 → 用 `@IntegrationTest` 组合注解而不是基类）、`data-factories.md`（具名常量：`MutableClock.DEFAULT_INSTANT`、`TestDatabase.FORBIDDEN_PORTS`）。

## 下游替身（WireMock）

不引入 WireMock：本服务不调用任何下游服务（AD-6、AD-12，下游契约清单为空），没有需要替身的出站 HTTP。
将来新增下游调用时，先按 AD-6 新增 AD、在 `docs/contracts/` 补下游契约，再引入替身。
