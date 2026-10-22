package com.todoapp.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 纯单元测试示例：不起 Spring、不连库，毫秒级。纯逻辑（如 BE-1 的 TitleRules）照这个形状写。 */
@DisplayName("MutableClock")
class MutableClockTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
    }

    @Test
    @DisplayName("默认冻结在 DEFAULT_INSTANT、时区 UTC，且不做毫秒截断")
    void startsFrozenAtDefaultInstant() {
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-15T02:30:00.123456Z"));
        assertThat(clock.instant().getNano() % 1_000_000).isEqualTo(456_000);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("advance 支持前进和回拨，reset 回到默认时刻")
    void advancesRewindsAndResets() {
        clock.advance(Duration.ofMillis(1));
        assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT.plusMillis(1));

        clock.advance(Duration.ofSeconds(-10));
        assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT.plusMillis(1).minusSeconds(10));

        clock.reset();
        assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT);
    }

    @Test
    @DisplayName("withZone 返回的视图与原时钟共享同一时刻")
    void zonedViewSharesTheSameInstant() {
        Clock shanghai = clock.withZone(ZoneId.of("Asia/Shanghai"));
        Instant target = Instant.parse("2026-03-01T00:00:00Z");

        clock.setInstant(target);

        assertThat(shanghai.instant()).isEqualTo(target);
        assertThat(shanghai.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }
}
