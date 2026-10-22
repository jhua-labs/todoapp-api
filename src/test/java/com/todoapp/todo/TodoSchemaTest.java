package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.TodoSeeder;

/**
 * Story 2.1 AC-1（BE-G4 唯一索引部分、R-3；AD-3、AD-5）：Flyway 执行 {@code V1__create_todo.sql} 建出的
 * {@code todo} 表逐项对照 {@code ddl.sql}，约束行为精确到约束名；Flyway 设置保持 validate-on-migrate、
 * clean-disabled，不开 baseline / out-of-order。
 *
 * <p>「同毫秒新增仍 max+1ms 递增」属于 Story 2.2，这里只证唯一索引本身。
 * 全文级的 schema 一致性由 {@link SchemaDriftTest}（BE-S1）负责。
 */
@IntegrationTest
@DisplayName("Story 2.1 todo 表与 Flyway 设置")
class TodoSchemaTest {

    private static final Instant T0 = Instant.parse("2026-01-15T02:30:00.000Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Environment env;

    @Test
    @DisplayName("[P0] AC-1 BE-G4 V1 建出的 todo 表列/默认值/约束/唯一索引对照 ddl.sql，约束按名生效，Flyway 设置不放宽")
    void v1CreatesTodoTableExactlyAsDdl() {
        // Given 应用启动（@IntegrationTest 上下文启动时 Flyway 已在测试库上迁移）
        // Then 表由 Flyway 建出——本 AC 最先可能失败的断言
        assertThat(jdbc.sql("SELECT coalesce(to_regclass('public.todo')::text, '<none>')").query(String.class).single())
                .as("todo 表存在（由 V1 建出）")
                .isEqualTo("todo");

        // 迁移历史：恰好一条 V1「create todo」且成功
        List<String> history = jdbc.sql("""
                        SELECT version || '|' || description || '|' || success
                          FROM flyway_schema_history
                         WHERE version IS NOT NULL
                         ORDER BY installed_rank
                        """).query(String.class).list();
        assertThat(history).containsExactly("1|create todo|true");

        // 列：名、类型、NOT NULL、默认值，按列序
        List<String> columns = jdbc.sql("""
                        SELECT a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                               || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
                               || coalesce(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '')
                          FROM pg_attribute a
                          LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                         WHERE a.attrelid = 'public.todo'::regclass AND a.attnum > 0 AND NOT a.attisdropped
                         ORDER BY a.attnum
                        """).query(String.class).list();
        assertThat(columns).containsExactly(
                "id uuid NOT NULL DEFAULT gen_random_uuid()",
                "title text NOT NULL",
                "completed boolean NOT NULL DEFAULT false",
                "created_at timestamp(3) with time zone NOT NULL",
                "updated_at timestamp(3) with time zone NOT NULL");

        // 具名约束（PG 18 还会把 NOT NULL 记成 *_not_null 约束，按类型 p/c/u 过滤）
        List<String> constraints = jdbc.sql("""
                        SELECT conname || ' ' || pg_get_constraintdef(oid)
                          FROM pg_constraint
                         WHERE conrelid = 'public.todo'::regclass AND contype IN ('p', 'c', 'u')
                         ORDER BY conname
                        """).query(String.class).list();
        assertThat(constraints).containsExactly(
                "todo_pk PRIMARY KEY (id)",
                "todo_title_length_chk CHECK (((char_length(title) >= 1) AND (char_length(title) <= 200)))",
                "todo_updated_after_chk CHECK ((updated_at >= created_at))");

        // 索引：主键索引 + created_at DESC 唯一索引，再无其他
        List<String> indexes = jdbc.sql("""
                        SELECT pg_get_indexdef(indexrelid) FROM pg_index
                         WHERE indrelid = 'public.todo'::regclass ORDER BY 1
                        """).query(String.class).list();
        assertThat(indexes).containsExactly(
                "CREATE UNIQUE INDEX todo_created_at_uq ON public.todo USING btree (created_at DESC)",
                "CREATE UNIQUE INDEX todo_pk ON public.todo USING btree (id)");

        // 默认值行为：不给 id / completed 时由 DB 生成 uuid、completed = false
        UUID id = jdbc.sql("INSERT INTO todo (title, created_at, updated_at) VALUES (:t, :c, :c) RETURNING id")
                .param("t", "买牛奶").param("c", utc(T0)).query(UUID.class).single();
        assertThat(id.toString()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(jdbc.sql("SELECT completed FROM todo WHERE id = :id").param("id", id).query(Boolean.class).single())
                .isFalse();

        // 约束行为，精确到 SQLState + 约束名
        assertViolation(insertRaw("", T0.plusMillis(10), T0.plusMillis(10)), "23514", "todo_title_length_chk");
        assertViolation(insertRaw("字".repeat(201), T0.plusMillis(11), T0.plusMillis(11)), "23514", "todo_title_length_chk");
        assertViolation(insertRaw("回拨", T0.plusMillis(12), T0.plusMillis(11)), "23514", "todo_updated_after_chk");
        assertViolation(insertRaw("同一毫秒", T0, T0), "23505", "todo_created_at_uq");

        // 边界：200 码点（含增补平面字符）能写入
        TodoSeeder.insert(jdbc, TodoSeeder.Row.created("😀".repeat(200), false, T0.plusMillis(20)));

        // Flyway 设置（AD-5）：validate-on-migrate 开、clean 禁用，不开 baseline / out-of-order
        assertThat(env.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.validate-on-migrate")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false)).isFalse();
        assertThat(env.getProperty("spring.flyway.out-of-order", Boolean.class, false)).isFalse();
    }

    private Throwable insertRaw(String title, Instant createdAt, Instant updatedAt) {
        Map<String, Object> params = Map.of(
                "title", title, "createdAt", utc(createdAt), "updatedAt", utc(updatedAt));
        return catchThrowable(() -> jdbc
                .sql("INSERT INTO todo (title, created_at, updated_at) VALUES (:title, :createdAt, :updatedAt)")
                .params(params)
                .update());
    }

    private static void assertViolation(Throwable thrown, String sqlState, String constraint) {
        assertThat(thrown).as("应违反 %s", constraint).isNotNull();
        SQLException sql = rootSqlException(thrown);
        // SQLException 实现了 Iterable，显式按 Throwable 断言以避免重载歧义
        assertThat((Throwable) sql).as("异常链里应有 SQLException：%s", thrown).isNotNull();
        assertThat(sql.getSQLState()).as("SQLState（%s）", constraint).isEqualTo(sqlState);
        assertThat(sql.getMessage()).contains("\"" + constraint + "\"");
    }

    private static SQLException rootSqlException(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof SQLException s) {
                return s;
            }
        }
        return null;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
