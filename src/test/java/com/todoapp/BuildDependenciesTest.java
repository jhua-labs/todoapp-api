package com.todoapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Story 1.1 AC-1（INF-B1）：运行时依赖到位、ORM 不在 classpath（AD-3）。
 *
 * <p>说明：4.2 已交付 pom，本测试激活后预期<b>直接为绿</b>，属于回归守卫；
 * 「产出可运行 jar、jar 不含 zonky」在 surefire 之后才能证明，由 Story 1.1 T8.1 手工核对。
 * pom 层面的禁止依赖另由 maven-enforcer 守住，这里从测试 classpath 再断一遍，防止有人绕过 enforcer。
 */
@DisplayName("Story 1.1 构建与依赖")
class BuildDependenciesTest {

    @ParameterizedTest(name = "{0} 应{1}在 classpath 上")
    @CsvSource({
            // 必须存在：webmvc、jdbc（JdbcClient）、flyway + flyway-database-postgresql、PostgreSQL JDBC、webmvc-test（MockMvc）
            "org.springframework.web.servlet.DispatcherServlet,              存在",
            "org.springframework.jdbc.core.simple.JdbcClient,                存在",
            "org.flywaydb.core.Flyway,                                       存在",
            "org.flywaydb.database.postgresql.PostgreSQLDatabaseType,        存在",
            "org.postgresql.Driver,                                          存在",
            "org.springframework.test.web.servlet.MockMvc,                   存在",
            // 必须不存在：JPA / Hibernate / Spring Data JPA（AD-3）
            "jakarta.persistence.Entity,                                     不存在",
            "org.hibernate.Session,                                          不存在",
            "org.springframework.data.jpa.repository.JpaRepository,          不存在"
    })
    @DisplayName("[P0] AC-1 INF-B1 依赖到位且无 ORM")
    void requiredDependenciesPresentAndOrmAbsent(String className, String expectation) {
        boolean shouldBePresent = expectation.equals("存在");

        assertThat(isLoadable(className))
                .as("%s 应%s在 classpath 上", className, expectation)
                .isEqualTo(shouldBePresent);
    }

    private static boolean isLoadable(String className) {
        try {
            Class.forName(className, false, BuildDependenciesTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
