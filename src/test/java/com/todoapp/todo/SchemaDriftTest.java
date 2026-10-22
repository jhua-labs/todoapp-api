package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.todoapp.support.TestDatabase;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Story 2.1 AC-2（BE-S1，R-3，AD-5）：在测试 embedded PG 实例上建两个临时空库，
 * 库 A 跑 Flyway（{@code classpath:db/migration}），库 B 执行 {@code ddl.sql} 全文，
 * 两边的 schema 快照完全相等，且 {@code server_encoding = UTF8}。
 *
 * <p><b>比对方式（决策闸【已定】2026-10-20，PM 选方案 2）：</b>不依赖 pg_dump（zonky 18.6.0 二进制不带它），
 * 改为对 {@code pg_catalog} 做目录查询取快照。范围：只取 {@code public} schema、排除 {@code flyway_schema_history}；
 * 关系（名字 + relkind）、列（序号、{@code format_type}、{@code pg_get_expr} 默认值、NOT NULL）、
 * 约束（类型 + {@code pg_get_constraintdef}）、索引（{@code pg_get_indexdef}）、扩展（名字 + 版本）。
 * 快照按对象名排序（确定性比较，不是为了凑一致），不一致时逐项列出哪个对象、哪一项不同。
 *
 * <p>纯 JUnit（不起 Spring 上下文）。绝不连 54329 / 5432：只用 {@link TestDatabase} 启动的实例。
 */
@DisplayName("Story 2.1 schema 比对（Flyway vs ddl.sql）")
class SchemaDriftTest {

    /** ddl.sql 相对 todoapp-api basedir（surefire 工作目录）的路径；只读共享物（AD-1）。 */
    static final Path DDL = Path.of(
            "../_bmad-output/planning-artifacts/architecture/architecture-todoapp-2026-10-19/ddl.sql");

    static final String EXCLUDED_TABLE = "flyway_schema_history";

    @Test
    @DisplayName("[P0] AC-2 BE-S1 两个空库分别跑 Flyway 与 ddl.sql，目录查询 schema 快照完全一致且 server_encoding = UTF8")
    void flywaySchemaMatchesDdl() throws Exception {
        EmbeddedPostgres pg = TestDatabase.get();
        JdbcClient admin = JdbcClient.create(pg.getPostgresDatabase());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String dbFlyway = "drift_flyway_" + suffix;
        String dbDdl = "drift_ddl_" + suffix;
        // 库名只含字母数字下划线（上面生成），不含外部输入
        admin.sql("CREATE DATABASE " + dbFlyway).update();
        admin.sql("CREATE DATABASE " + dbDdl).update();
        try {
            // Given 库 A：Flyway
            DataSource flywayDs = pg.getDatabase(TestDatabase.USER, dbFlyway);
            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(TestDatabase.USER, dbFlyway), TestDatabase.USER, "")
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            // Given 库 B：ddl.sql 全文（文件不存在 → 抛 NoSuchFileException，测试失败而不是跳过）
            String ddl = Files.readString(DDL, StandardCharsets.UTF_8);
            DataSource ddlDs = pg.getDatabase(TestDatabase.USER, dbDdl);
            try (Connection c = ddlDs.getConnection(); Statement st = c.createStatement()) {
                st.execute(ddl);
            }

            // When 两边各取 schema 快照
            SortedMap<String, String> flywaySchema = snapshot(flywayDs);
            SortedMap<String, String> ddlSchema = snapshot(ddlDs);

            // Then 快照确实取到了 todo 表的结构（防止两边都空而「一致」）
            assertThat(ddlSchema).containsKeys(
                    "relation todo",
                    "column todo.id",
                    "column todo.title",
                    "column todo.completed",
                    "column todo.created_at",
                    "column todo.updated_at",
                    "constraint todo.todo_pk",
                    "constraint todo.todo_title_length_chk",
                    "constraint todo.todo_updated_after_chk",
                    "index todo_created_at_uq");
            assertThat(flywaySchema.keySet()).noneMatch(k -> k.contains(EXCLUDED_TABLE));

            // Then 完全一致——本 AC 的判定断言
            assertThat(diff(flywaySchema, ddlSchema))
                    .as("Flyway（左）与 ddl.sql（右）的 schema 快照差异")
                    .isEmpty();
            assertThat(flywaySchema).isEqualTo(ddlSchema);

            // And 两库 server_encoding = UTF8
            assertThat(encoding(flywayDs)).isEqualTo("UTF8");
            assertThat(encoding(ddlDs)).isEqualTo("UTF8");
        } finally {
            admin.sql("DROP DATABASE IF EXISTS " + dbFlyway + " WITH (FORCE)").update();
            admin.sql("DROP DATABASE IF EXISTS " + dbDdl + " WITH (FORCE)").update();
        }
    }

    @Test
    @DisplayName("[P0] AC-2 BE-S1 负向自检：只差一个约束名或一个列类型的快照判为不一致，并指出对象与项")
    void diffDetectsSingleDifference() {
        SortedMap<String, String> base = new TreeMap<>(Map.of(
                "relation todo", "relkind=r",
                "column todo.title", "ordinal=2 type=text default=null notNull=true",
                "constraint todo.todo_pk", "type=p def=PRIMARY KEY (id)",
                "index todo_created_at_uq", "CREATE UNIQUE INDEX todo_created_at_uq ON public.todo USING btree (created_at DESC)"));
        assertThat(diff(base, new TreeMap<>(base))).as("相同快照").isEmpty();

        // 只差一个约束名
        SortedMap<String, String> renamed = new TreeMap<>(base);
        renamed.put("constraint todo.todo_pkey", renamed.remove("constraint todo.todo_pk"));
        assertThat(diff(base, renamed)).containsExactly(
                "只在左侧：constraint todo.todo_pk = type=p def=PRIMARY KEY (id)",
                "只在右侧：constraint todo.todo_pkey = type=p def=PRIMARY KEY (id)");

        // 只差一个列类型
        SortedMap<String, String> retyped = new TreeMap<>(base);
        retyped.put("column todo.title", "ordinal=2 type=character varying(200) default=null notNull=true");
        assertThat(diff(base, retyped)).containsExactly(
                "不同：column todo.title 左=ordinal=2 type=text default=null notNull=true"
                        + " 右=ordinal=2 type=character varying(200) default=null notNull=true");
    }

    /**
     * 取 public schema 的快照（排除 {@code flyway_schema_history}）。键 = 对象类别 + 名字，值 = 该对象的全部比较项。
     * 包级可见：负向自检与排错时可直接调用。
     */
    static SortedMap<String, String> snapshot(DataSource ds) {
        JdbcClient jdbc = JdbcClient.create(ds);
        SortedMap<String, String> snap = new TreeMap<>();

        jdbc.sql("""
                SELECT c.relname, c.relkind::text AS relkind
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname NOT LIKE :excluded
                ORDER BY c.relname
                """)
                .param("excluded", EXCLUDED_TABLE + "%")
                .query((rs, i) -> {
                    snap.put("relation " + rs.getString("relname"), "relkind=" + rs.getString("relkind"));
                    return null;
                })
                .list();

        jdbc.sql("""
                SELECT c.relname, a.attname, a.attnum,
                       format_type(a.atttypid, a.atttypmod) AS type,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expr,
                       a.attnotnull
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND c.relname <> :excluded AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY c.relname, a.attname
                """)
                .param("excluded", EXCLUDED_TABLE)
                .query((rs, i) -> {
                    snap.put("column " + rs.getString("relname") + "." + rs.getString("attname"),
                            "ordinal=" + rs.getInt("attnum")
                                    + " type=" + rs.getString("type")
                                    + " default=" + rs.getString("default_expr")
                                    + " notNull=" + rs.getBoolean("attnotnull"));
                    return null;
                })
                .list();

        jdbc.sql("""
                SELECT c.relname, con.conname, con.contype::text AS contype,
                       pg_get_constraintdef(con.oid) AS def
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = con.connamespace
                WHERE n.nspname = 'public' AND c.relname <> :excluded
                ORDER BY c.relname, con.conname
                """)
                .param("excluded", EXCLUDED_TABLE)
                .query((rs, i) -> {
                    snap.put("constraint " + rs.getString("relname") + "." + rs.getString("conname"),
                            "type=" + rs.getString("contype") + " def=" + rs.getString("def"));
                    return null;
                })
                .list();

        jdbc.sql("""
                SELECT ic.relname AS index_name, pg_get_indexdef(ix.indexrelid) AS def
                FROM pg_index ix
                JOIN pg_class ic ON ic.oid = ix.indexrelid
                JOIN pg_class tc ON tc.oid = ix.indrelid
                JOIN pg_namespace n ON n.oid = tc.relnamespace
                WHERE n.nspname = 'public' AND tc.relname <> :excluded
                ORDER BY ic.relname
                """)
                .param("excluded", EXCLUDED_TABLE)
                .query((rs, i) -> {
                    snap.put("index " + rs.getString("index_name"), rs.getString("def"));
                    return null;
                })
                .list();

        jdbc.sql("SELECT extname, extversion FROM pg_extension ORDER BY extname")
                .query((rs, i) -> {
                    snap.put("extension " + rs.getString("extname"), "version=" + rs.getString("extversion"));
                    return null;
                })
                .list();

        return snap;
    }

    /** 逐项列出两份快照的差异（按对象名排序）；空列表 = 完全一致。包级可见：负向自检直接调用。 */
    static List<String> diff(SortedMap<String, String> left, SortedMap<String, String> right) {
        List<String> out = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        for (String key : keys) {
            boolean inLeft = left.containsKey(key);
            boolean inRight = right.containsKey(key);
            if (inLeft && !inRight) {
                out.add("只在左侧：" + key + " = " + left.get(key));
            } else if (!inLeft) {
                out.add("只在右侧：" + key + " = " + right.get(key));
            } else if (!Objects.equals(left.get(key), right.get(key))) {
                out.add("不同：" + key + " 左=" + left.get(key) + " 右=" + right.get(key));
            }
        }
        return out;
    }

    private static String encoding(DataSource ds) throws SQLException {
        return JdbcClient.create(ds).sql("SHOW server_encoding").query(String.class).single();
    }
}
