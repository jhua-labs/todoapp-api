package com.todoapp.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 测试专用的 embedded PostgreSQL 18.6（AD-16）：随机端口 + 临时数据目录，JVM 内只启动一次，
 * 所有 Spring 测试上下文共用；JVM 退出时由 zonky 的 shutdown hook 停库并删除数据目录。
 *
 * <p>和本地开发库（AD-14：固定端口 54329、持久目录 .local-pg/）、brew 退路（5432）完全隔离。
 *
 * <p>initdb 用 {@code --locale=C}：各开发机 / CI 结果一致，不依赖系统是否装了某个 locale。
 * staging 镜像的排序规则是 en_US.utf8，与此不同；因此业务代码不得依赖文本排序规则或
 * locale 相关的大小写转换（排序只按 {@code created_at}，AD-9）。
 */
public final class TestDatabase {

    /** 测试绝不能连上的端口：本地开发 embedded 库与 brew PostgreSQL。 */
    public static final Set<Integer> FORBIDDEN_PORTS = Set.of(54329, 5432);

    public static final String DATABASE = "postgres";
    public static final String USER = "postgres";

    /** 随机端口偶尔会落在保留端口上（macOS 临时端口段包含 54329），换一个端口重试。 */
    private static final int MAX_START_ATTEMPTS = 5;

    private static volatile EmbeddedPostgres instance;

    private TestDatabase() {
    }

    public static EmbeddedPostgres get() {
        EmbeddedPostgres local = instance;
        if (local == null) {
            synchronized (TestDatabase.class) {
                local = instance;
                if (local == null) {
                    local = start();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl(USER, DATABASE);
    }

    private static EmbeddedPostgres start() {
        try {
            for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
                EmbeddedPostgres pg = EmbeddedPostgres.builder()
                        // initdb 显式 UTF8：char_length 按码点计依赖于此（ddl.sql 头注释、AD-5 漂移检查）
                        .setLocaleConfig("encoding", "UTF8")
                        .setLocaleConfig("locale", "C")
                        .setServerConfig("listen_addresses", "localhost")
                        .start();
                if (!FORBIDDEN_PORTS.contains(pg.getPort())) {
                    return pg;
                }
                pg.close();
            }
            throw new IllegalStateException("embedded PG 连续 " + MAX_START_ATTEMPTS + " 次拿到保留端口，放弃");
        } catch (IOException e) {
            throw new UncheckedIOException("embedded PostgreSQL 启动失败", e);
        }
    }
}
