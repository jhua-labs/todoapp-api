package com.todoapp.todo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code todo} 表的唯一业务读写入口（AD-4）。只执行 SQL 并映射结果行，不含业务分支（AD-2）；
 * 只用 {@link JdbcClient} 手写 SQL，有参数时只用命名参数（AD-3）。不加 {@code @Transactional}（AD-6）。
 */
@Repository
public class TodoRepository {

    /** GET /api/todos：全部待办，最新的在前（FR-3、FR-4）。不分页：500 是硬上限（FR-2）。 */
    private static final String FIND_ALL_NEWEST_FIRST = """
            SELECT id, title, completed, created_at, updated_at
              FROM todo
             ORDER BY created_at DESC
            """;

    /**
     * 容量锁的常量键（AD-10）：只在这里定义，只有新增取这把锁。包级可见仅供同包测试（BE-P8）在另一连接上占锁。
     * 值是 ASCII "todocapa" 的 8 字节，固定不变。
     */
    static final long CAPACITY_LOCK_KEY = 0x746F_646F_6361_7061L;

    /** 取锁前设置锁等待上限（AD-10）：常量 SQL、不含用户输入；{@code SET LOCAL} 只作用于当前事务。 */
    private static final String SET_LOCK_TIMEOUT = "SET LOCAL lock_timeout = '2s'";

    /** 事务级 advisory 锁（AD-10）：提交 / 回滚时自动释放，把并发新增串行化（FR-2 硬上限）。 */
    private static final String LOCK_CAPACITY = "SELECT pg_advisory_xact_lock(:key)";

    /** 锁内容量快照：总数（FR-2 判满）与最新 created_at（AD-9 严格递增）。 */
    private static final String CAPACITY = """
            SELECT count(*) AS n, max(created_at) AS latest
              FROM todo
            """;

    /** POST /api/todos（FR-1）：id 由 DB gen_random_uuid() 生成，响应用 RETURNING 的结果构造（AD-9）。 */
    private static final String INSERT = """
            INSERT INTO todo (title, completed, created_at, updated_at)
            VALUES (:title, false, :at, :at)
            RETURNING id, title, completed, created_at, updated_at
            """;

    /**
     * PATCH /api/todos/{id}（FR-5、FR-6、AD-11）：一条常量 SQL，不先读后写、不取容量锁、不做版本检测（last-write-wins）。
     * {@code :title} / {@code :completed} 为 null 表示请求未带该字段、保持原值；SET 右侧引用的列在 PG 中一律是旧值，
     * 所以 CASE 比较的是「新值 vs 旧值」：只有值真的变了才把 updated_at 改成 {@code GREATEST(:now, created_at)}
     * （时钟回拨时不早于 created_at，不触发 todo_updated_after_chk）。无返回行 = id 不存在。
     */
    private static final String UPDATE = """
            UPDATE todo
               SET title      = COALESCE(CAST(:title AS text), title),
                   completed  = COALESCE(CAST(:completed AS boolean), completed),
                   updated_at = CASE
                                  WHEN COALESCE(CAST(:title AS text), title) IS DISTINCT FROM title
                                    OR COALESCE(CAST(:completed AS boolean), completed) IS DISTINCT FROM completed
                                  THEN GREATEST(:now, created_at)
                                  ELSE updated_at
                                END
             WHERE id = :id
            RETURNING id, title, completed, created_at, updated_at
            """;

    /**
     * DELETE /api/todos/{id}（FR-7、AD-11）：一条常量 SQL，不先读后写、不取容量锁、不 RETURNING（204 无响应体）。
     * 影响 0 行 = id 不存在，由 service 判定为 404。
     */
    private static final String DELETE = """
            DELETE FROM todo
             WHERE id = :id
            """;

    private final JdbcClient jdbc;

    public TodoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Todo> findAllNewestFirst() {
        return jdbc.sql(FIND_ALL_NEWEST_FIRST).query(TodoRepository::mapRow).list();
    }

    /** 必须在调用方事务内、取锁之前执行（事务外 PG 会忽略 {@code SET LOCAL}）。 */
    public void setLockTimeout() {
        jdbc.sql(SET_LOCK_TIMEOUT).update();
    }

    /**
     * 阻塞直到拿到容量锁；超过 lock_timeout 时 PG 报 55P03，异常原样上抛（→ 500）。
     * {@code pg_advisory_xact_lock} 返回 void：用 {@code query(RowCallbackHandler)} 执行，不用 {@code update()}。
     */
    public void lockCapacity() {
        jdbc.sql(LOCK_CAPACITY).param("key", CAPACITY_LOCK_KEY).query((RowCallbackHandler) rs -> { });
    }

    public CapacitySnapshot capacity() {
        return jdbc.sql(CAPACITY).query((rs, rowNum) -> {
            OffsetDateTime latest = rs.getObject("latest", OffsetDateTime.class);
            return new CapacitySnapshot(rs.getLong("n"), latest == null ? null : latest.toInstant());
        }).single();
    }

    /** {@code at} 同时写入 created_at 与 updated_at；按 UTC 的 {@code OffsetDateTime} 绑定，不经 JVM 默认时区。 */
    public Todo insert(String title, Instant at) {
        return jdbc.sql(INSERT)
                .param("title", title)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(TodoRepository::mapRow)
                .single();
    }

    /**
     * {@code title} / {@code completed} 为 null 表示不改该字段；null 也带 SQL 类型绑定（配合 SQL 里的 CAST），
     * 避免 pgjdbc 对无类型 null 报错。{@code now} 按 UTC 的 {@code OffsetDateTime} 绑定。id 不存在时返回空。
     */
    public Optional<Todo> update(UUID id, String title, Boolean completed, Instant now) {
        return jdbc.sql(UPDATE)
                .param("id", id)
                .param("title", title, Types.VARCHAR)
                .param("completed", completed, Types.BOOLEAN)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(TodoRepository::mapRow)
                .optional();
    }

    /** 返回影响行数（0 或 1）；是否 404 由 service 决定（AD-2）。 */
    public int delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update();
    }

    /** 容量快照：{@code latest} 在空表时为 null。 */
    public record CapacitySnapshot(long count, Instant latest) {
    }

    /**
     * 按 {@code OffsetDateTime} 读 {@code timestamptz}：不经过 JVM 默认时区
     * （{@code getTimestamp().toLocalDateTime()} 会在 {@code Asia/Shanghai} 下平移 8 小时）。
     */
    private static Todo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Todo(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getBoolean("completed"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
