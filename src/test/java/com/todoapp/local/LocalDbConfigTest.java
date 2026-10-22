package com.todoapp.local;

import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/** Story 1.1 INF-B2 / AD-14：本地持久库配置的纯单元回归测试，不启动或连接开发数据库。 */
@DisplayName("Story 1.1 INF-B2 本地持久库配置")
class LocalDbConfigTest {

    @Test
    @DisplayName("[P0] INF-B2 本地持久库同时启用 fsync 与 synchronous_commit")
    void enablesCrashSafeDurability() throws Exception {
        EmbeddedPostgres.Builder builder = mock(EmbeddedPostgres.Builder.class, RETURNS_SELF);

        try (MockedStatic<EmbeddedPostgres> embeddedPostgres = mockStatic(EmbeddedPostgres.class)) {
            embeddedPostgres.when(EmbeddedPostgres::builder).thenReturn(builder);

            new LocalDbConfig().localEmbeddedPostgres();

            verify(builder).setServerConfig("fsync", "on");
            verify(builder).setServerConfig("synchronous_commit", "on");
        }
    }
}
