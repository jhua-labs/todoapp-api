package com.todoapp.local;

import java.io.IOException;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 本地开发库（AD-14）：embedded PostgreSQL 18.6，固定端口 {@value #PORT}，数据目录 {@code todoapp-api/.local-pg/}
 * 持久保留。只通过 {@link LocalDevApplication} 的 {@code .with(LocalDbConfig.class)} 引入。
 *
 * <ul>
 *   <li>{@code @TestConfiguration}：测试上下文的组件扫描会排除它；</li>
 *   <li>{@code @Profile("local")}：即使被扫到，非 local 运行也不生效；</li>
 *   <li>{@code todoapp.local-db.embedded=false} 时整个配置不生效，改用 {@code application-local.yaml} 的 brew 退路。</li>
 * </ul>
 *
 * <p>embedded 模式下 DataSource 只来自这里（Boot 的 DataSource 自动配置见到已有 bean 会退让，Flyway 也用它）。
 * 测试绝不能启动这个配置：它连的是开发者自己的本地数据。
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "todoapp.local-db.embedded", havingValue = "true", matchIfMissing = true)
public class LocalDbConfig {

    /** 本地开发库固定端口（AD-14）；测试库的 {@code TestDatabase.FORBIDDEN_PORTS} 包含它。 */
    public static final int PORT = 54329;

    /**
     * 相对路径：{@code spring-boot:test-run} 的工作目录默认是项目 basedir，
     * 即使在工作区根用 {@code -f todoapp-api/pom.xml} 执行，也落在 {@code todoapp-api/.local-pg/}。
     */
    static final Path DATA_DIRECTORY = Path.of(".local-pg");

    static final String DATABASE = "postgres";
    static final String USER = "postgres";

    @Bean(destroyMethod = "close")
    EmbeddedPostgres localEmbeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(PORT)
                .setDataDirectory(DATA_DIRECTORY)
                .setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "localhost")
                // zonky 以 -F（fsync=off）启动；持久库必须显式覆盖，避免崩溃时丢失已确认写入（FR-8）
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                // 码点长度校验依赖 UTF8（AD-7）
                .setLocaleConfig("encoding", "UTF8")
                .start();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres localEmbeddedPostgres) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(localEmbeddedPostgres.getJdbcUrl(USER, DATABASE));
        ds.setUsername(USER);
        // 日志不记标题：与 application.yaml 的 hikari.data-source-properties 一致
        ds.addDataSourceProperty("logServerErrorDetail", "false");
        return ds;
    }
}
