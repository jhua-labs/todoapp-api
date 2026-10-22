package com.todoapp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用的冻结时钟（AD-9 / AD-16，风险 R-7）。时间只在测试显式调用 {@link #setInstant} / {@link #advance} 时变化，
 * 每个集成测试开始前由 {@link ResetTestStateListener} 复位到 {@link #DEFAULT_INSTANT}。
 *
 * <p>不做毫秒截断：AD-9 规定截断只发生在 {@code TodoService.now()}，时钟 bean 本身不截断。
 */
public final class MutableClock extends Clock {

    /**
     * 默认冻结时刻。故意带非零的微秒部分（.123456），用来证明截断确实发生在 {@code TodoService.now()}，
     * 而不是靠时钟恰好落在整毫秒上。
     */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-15T02:30:00.123456Z");

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicReference<>(DEFAULT_INSTANT), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
        this.current = current;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** 返回共享同一时刻的视图：对任一视图 set / advance，其他视图同步可见。 */
    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(current, Objects.requireNonNull(zone, "zone"));
    }

    @Override
    public Instant instant() {
        return current.get();
    }

    public void setInstant(Instant instant) {
        current.set(Objects.requireNonNull(instant, "instant"));
    }

    /** 时钟前进（或传负值回拨，用来构造 AD-11 的时钟回拨场景）。 */
    public void advance(Duration duration) {
        current.updateAndGet(i -> i.plus(Objects.requireNonNull(duration, "duration")));
    }

    public void reset() {
        current.set(DEFAULT_INSTANT);
    }
}
