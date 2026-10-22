package com.todoapp.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 每个集成测试前清空业务表（AD-16「每个测试前清空 todo」）。
 *
 * <p>按目录查出 public schema 下除 {@code flyway_schema_history} 之外的全部表一次 TRUNCATE，
 * 后续 story 新增表时不用改这里。
 *
 * <p>安全：清表前向<b>服务端</b>查询实际端口，必须等于本 JVM 启动的测试库端口（白名单），
 * 绝不清本地开发库（54329）、brew 库（5432）或任何别的库。
 */
public class DatabaseCleaner {

    private static final String LIST_TABLES = """
            SELECT format('%I.%I', schemaname, tablename)
              FROM pg_tables
             WHERE schemaname = 'public'
               AND tablename <> 'flyway_schema_history'
             ORDER BY tablename
            """;

    /** 遇到上一个失败测试遗留的锁时，清表最多等这么久就失败，而不是把整套测试挂住。 */
    static final String LOCK_TIMEOUT = "5s";

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public DatabaseCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void clean() {
        assertConnectedToTestDatabase();
        List<String> tables = jdbc.sql(LIST_TABLES).query(String.class).list();
        if (tables.isEmpty()) {
            return;
        }
        // 表名来自 pg_tables 并经 format('%I') 加引号，不含任何用户输入
        String truncate = "TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE";
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
                st.execute(truncate);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清表失败（可能有上一个测试遗留的未结束事务持有锁）", e);
        }
    }

    /** 服务端报告的实际端口（{@code current_setting('port')}），不依赖 JDBC URL 的写法。 */
    public int connectedPort() {
        return jdbc.sql("SELECT current_setting('port')::int").query(Integer.class).single();
    }

    private void assertConnectedToTestDatabase() {
        int actual = connectedPort();
        int expected = TestDatabase.get().getPort();
        if (actual != expected || TestDatabase.FORBIDDEN_PORTS.contains(actual)) {
            throw new IllegalStateException(
                    "拒绝清表：当前连接的库端口是 " + actual + "，不是本次测试启动的 embedded PG（" + expected + "）");
        }
    }
}
