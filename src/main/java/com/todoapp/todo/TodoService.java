package com.todoapp.todo;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.todoapp.common.error.LimitExceededException;
import com.todoapp.common.error.NotFoundException;
import com.todoapp.common.error.ValidationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 待办的业务规则（AD-2）。只调 {@link TodoRepository} 与 {@code config} bean（{@link Clock}）；
 * {@code @Transactional} 只加在这里的 public 方法上，读方法 {@code readOnly = true}（AD-6）。
 */
@Service
public class TodoService {

    /** 清单硬容量上限（FR-2）。只在应用层判定，DDL 层没有兜底（ddl.sql 注释）。 */
    static final int CAPACITY = 500;

    private final TodoRepository repository;
    private final Clock clock;

    public TodoService(TodoRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 全部待办，按 {@code createdAt} 从新到旧（FR-3、FR-4）。 */
    @Transactional(readOnly = true)
    public List<Todo> list() {
        return repository.findAllNewestFirst();
    }

    /**
     * 新增一条待办（FR-1、FR-2、AD-9、AD-10）。一个事务内按序：
     * 校验标题（无效输入不占锁）→ {@code SET LOCAL lock_timeout} → 取容量锁 → count + max(created_at)
     * → 满 500 抛 {@link LimitExceededException} → 否则以 {@code max(now, latest + 1ms)} 插入。
     *
     * <p>不 catch 锁超时 / SQL 异常：交给 {@code ApiErrorHandler} 兜底为 500（FR-2：后端失败不是「容量已满」）。
     * 隔离级别用默认 READ COMMITTED（AD-6）；并发正确性由 advisory 锁保证，不靠隔离级别。
     */
    @Transactional
    public Todo create(String rawTitle) {
        String title = TitleRules.normalize(rawTitle);
        repository.setLockTimeout();
        repository.lockCapacity();
        TodoRepository.CapacitySnapshot snapshot = repository.capacity();
        if (snapshot.count() >= CAPACITY) {
            throw new LimitExceededException("待办已满，请先清理");
        }
        Instant now = now();
        Instant latest = snapshot.latest();
        // created_at 全表严格递增：同毫秒或时钟回拨时取 latest + 1ms，updated_at 取同一值
        Instant at = (latest == null || now.isAfter(latest)) ? now : latest.plusMillis(1);
        return repository.insert(title, at);
    }

    /**
     * 修改一条待办（FR-5、FR-6、§11.3、§11.4、AD-8、AD-11）。按序：
     * 严格解析 id（非规范 → 404）→ 请求体三态判定（缺省 = 不改；显式 null / 类型不符 → 400；
     * 两字段都缺省（含 {@code {}} 与只含未知字段）→ 400）→ 标题走 {@link TitleRules#normalize}
     * → 单条 UPDATE（无行 → 404）。
     *
     * <p>类型判定必须在这里用节点方法做：{@code JacksonConfig} 的强转限制只作用于绑定到 Java 类型，
     * 对 {@link ObjectNode} 树不生效。不用 {@code asText()} / {@code asBoolean()} 这类会强转的方法。
     * 不取容量锁（AD-10）、不先读后写、不做版本检测（last-write-wins）；是否更新 updatedAt 由 SQL 的 CASE 决定。
     */
    @Transactional
    public Todo update(String rawId, ObjectNode body) {
        UUID id = TodoIds.parse(rawId);
        if (body == null) {
            throw new ValidationException("请求体格式不正确");
        }
        boolean hasTitle = body.has("title");
        boolean hasCompleted = body.has("completed");
        if (!hasTitle && !hasCompleted) {
            throw new ValidationException("请求体至少要包含 title 或 completed");
        }
        String title = null;
        if (hasTitle) {
            JsonNode node = body.get("title");
            if (node.isNull() || !node.isString()) {
                throw new ValidationException("title 必须是字符串");
            }
            title = TitleRules.normalize(node.stringValue());
        }
        Boolean completed = null;
        if (hasCompleted) {
            JsonNode node = body.get("completed");
            if (node.isNull() || !node.isBoolean()) {
                throw new ValidationException("completed 必须是布尔值");
            }
            completed = node.booleanValue();
        }
        return repository.update(id, title, completed, now())
                .orElseThrow(() -> new NotFoundException("待办不存在"));
    }

    /**
     * 删除一条待办（FR-7、FR-8、§11.3、§11.4、AD-8、AD-11）。按序：
     * 严格解析 id（非规范 → 404）→ 单条 DELETE（影响 0 行 → 404）。
     *
     * <p>不检查请求体（AD-8）、不取容量锁（AD-10：容量回落由下一次 create 在锁内 count 自然读到）、
     * 不写时间、不先读后写。SQL 异常不 catch，交给 {@code ApiErrorHandler} 兜底为 500。
     */
    @Transactional
    public void delete(String rawId) {
        UUID id = TodoIds.parse(rawId);
        if (repository.delete(id) == 0) {
            throw new NotFoundException("待办不存在");
        }
    }

    /**
     * 唯一取时入口（AD-9）：注入的 {@link Clock} 截断到毫秒（截断，不是四舍五入）。
     * 新增用它写 {@code created_at} / {@code updated_at}；修改把它交给 UPDATE，由 CASE 决定是否采用。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
