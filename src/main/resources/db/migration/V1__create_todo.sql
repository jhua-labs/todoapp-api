-- V1：todo 表首版（Story 2.1，BE-1）。
-- 逐项对照 _bmad-output/planning-artifacts/architecture/architecture-todoapp-2026-10-19/ddl.sql（表结构权威，AD-5）。
-- 已执行的迁移永不修改：之后的改表只新增 V{n}。
-- gen_random_uuid() 是 PostgreSQL 13+ 内置函数，无需 CREATE EXTENSION。

-- owner: com.todoapp.todo.TodoRepository
CREATE TABLE todo (
    id          uuid           NOT NULL DEFAULT gen_random_uuid(),
    title       text           NOT NULL,
    completed   boolean        NOT NULL DEFAULT false,
    created_at  timestamptz(3) NOT NULL,
    updated_at  timestamptz(3) NOT NULL,

    CONSTRAINT todo_pk                 PRIMARY KEY (id),
    CONSTRAINT todo_title_length_chk   CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT todo_updated_after_chk  CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX todo_created_at_uq ON todo (created_at DESC);
