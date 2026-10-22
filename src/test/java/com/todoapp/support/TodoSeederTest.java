package com.todoapp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.todoapp.support.TodoSeeder.Row;

/**
 * INF-B3（test-design TC-2，Story 2.1 T4.2）：播种 helper 自检。证明 {@link TodoSeeder} 播出的数据
 * 真的满足 {@code ddl.sql} 的约束、{@code created_at} 严格递增，而不只是「没抛异常」。
 */
@IntegrationTest
@DisplayName("INF-B3 todo 批量播种 helper")
class TodoSeederTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("[P1] INF-B3 播 500 条：count = 500，created_at 互不相同且按 base + i ms 严格递增，全部满足表约束")
    void seedsFiveHundredRowsStrictlyIncreasing() {
        List<UUID> ids = TodoSeeder.seed(jdbc, 500);

        assertThat(ids).hasSize(500).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT count(*) FROM todo").query(Long.class).single()).isEqualTo(500L);
        assertThat(jdbc.sql("SELECT count(DISTINCT created_at) FROM todo").query(Long.class).single()).isEqualTo(500L);

        // 按插入顺序（id 与 rows 同序）取回 created_at：必须恰好是 base + i ms
        List<Instant> createdAt = jdbc.sql("""
                        SELECT created_at FROM todo ORDER BY created_at
                        """).query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).list();
        for (int i = 0; i < createdAt.size(); i++) {
            assertThat(createdAt.get(i)).as("第 %d 条 created_at", i).isEqualTo(TodoSeeder.DEFAULT_BASE.plusMillis(i));
        }

        // 约束（ddl.sql）：标题 1..200 码点、updated_at >= created_at；奇数条已完成
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM todo
                         WHERE char_length(title) BETWEEN 1 AND 200 AND updated_at >= created_at
                        """).query(Long.class).single()).isEqualTo(500L);
        assertThat(jdbc.sql("SELECT count(*) FROM todo WHERE completed").query(Long.class).single()).isEqualTo(250L);
    }

    @Test
    @DisplayName("[P1] INF-B3 Row 拒绝未截断到毫秒的时间与 updatedAt < createdAt")
    void rowRejectsInvalidTimes() {
        Instant base = TodoSeeder.DEFAULT_BASE;

        assertThatThrownBy(() -> Row.created("微秒", false, base.plusNanos(1_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdAt");
        assertThatThrownBy(() -> new Row("回拨", false, base, base.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("todo_updated_after_chk");
        assertThatThrownBy(() -> TodoSeeder.rows(MutableClock.DEFAULT_INSTANT, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base");
    }
}
