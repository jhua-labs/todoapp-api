package com.todoapp.support;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * INF-B3（test-design TC-2，Story 2.1 T4）：直接 SQL 往 {@code todo} 表播种测试数据。
 *
 * <p>纯静态工具，不是 Spring bean：不需要改 {@code TestInfrastructureConfig}，也不会被
 * {@code spring-boot:test-run} 扫进本地运行。依赖 V1 建出的 {@code todo} 表。
 *
 * <p>约束（{@code ddl.sql}）：{@code created_at} 全表唯一、{@code updated_at >= created_at}、标题 1..200 码点。
 * PostgreSQL 写 {@code timestamptz(3)} 时对多余精度<b>四舍五入</b>，所以这里只接受已截断到毫秒的时间，
 * 期望串与库值才不会差 1ms。{@code id} 由数据库 {@code gen_random_uuid()} 生成并经 {@code RETURNING id} 返回。
 *
 * <p>测试播种用直接 SQL 是 test-design 认可的做法，不算业务代码绕过 {@code TodoRepository}（AD-4）。
 */
public final class TodoSeeder {

    /** 批量播种的默认起点：冻结时钟默认时刻截断到毫秒后往前推一天，读起来一眼能认出。 */
    public static final Instant DEFAULT_BASE =
            MutableClock.DEFAULT_INSTANT.truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(1));

    private static final String INSERT = """
            INSERT INTO todo (title, completed, created_at, updated_at)
            VALUES (:title, :completed, :createdAt, :updatedAt)
            RETURNING id
            """;

    private TodoSeeder() {
    }

    /** 一行播种数据。时间必须已截断到毫秒，且 {@code updatedAt >= createdAt}。 */
    public record Row(String title, boolean completed, Instant createdAt, Instant updatedAt) {

        public Row {
            Objects.requireNonNull(title, "title");
            requireMillis(createdAt, "createdAt");
            requireMillis(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt 不得早于 createdAt（todo_updated_after_chk）");
            }
        }

        /** 未修改过的待办：{@code updatedAt = createdAt}。 */
        public static Row created(String title, boolean completed, Instant createdAt) {
            return new Row(title, completed, createdAt, createdAt);
        }
    }

    /** 插入一行，返回数据库生成的 id。 */
    public static UUID insert(JdbcClient jdbc, Row row) {
        return jdbc.sql(INSERT)
                .param("title", row.title())
                .param("completed", row.completed())
                .param("createdAt", OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC))
                .param("updatedAt", OffsetDateTime.ofInstant(row.updatedAt(), ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    /** 按给定顺序插入，返回 id（与入参同序）。 */
    public static List<UUID> seed(JdbcClient jdbc, List<Row> rows) {
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Row row : rows) {
            ids.add(insert(jdbc, row));
        }
        return ids;
    }

    /** 从 {@link #DEFAULT_BASE} 起播 {@code n} 条，见 {@link #rows(Instant, int)}。 */
    public static List<UUID> seed(JdbcClient jdbc, int n) {
        return seed(jdbc, rows(DEFAULT_BASE, n));
    }

    /**
     * 生成 {@code n} 条：第 i 条（0 起）{@code createdAt = base + i ms}（严格递增、互不相同），
     * {@code updatedAt = createdAt}，标题 {@code 待办 #i}，偶数条未完成、奇数条已完成。
     */
    public static List<Row> rows(Instant base, int n) {
        requireMillis(base, "base");
        if (n < 0) {
            throw new IllegalArgumentException("n 不能为负：" + n);
        }
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(Row.created("待办 #" + i, i % 2 == 1, base.plusMillis(i)));
        }
        return rows;
    }

    private static void requireMillis(Instant instant, String name) {
        Objects.requireNonNull(instant, name);
        if (!instant.equals(instant.truncatedTo(ChronoUnit.MILLIS))) {
            throw new IllegalArgumentException(name + " 必须已截断到毫秒（PG 对 timestamptz(3) 四舍五入）：" + instant);
        }
    }
}
