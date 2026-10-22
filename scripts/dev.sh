#!/usr/bin/env bash
# 一条命令本地起全栈（Story 4.1 / DEV-1，AD-14）。
#
# 用法：在工作区根执行 todoapp-api/scripts/dev.sh；Ctrl-C（或对本脚本发 SIGTERM）一起停。
# - 路径一律以脚本自身所在目录为基准，从任何当前目录执行结果相同（AD-14）。
# - 启动前检查 Node 主版本为 24（AD-14、AD-18 的 .nvmrc）。
# - 并行起后端（spring-boot:test-run → LocalDevApplication，local profile、embedded PG 54329）
#   与前端（npm run dev，永不启用 MSW，AD-12）。监听地址 / 端口 / 代理全部来自
#   application.yaml 与 todoapp-web/vite.config.ts，本脚本不覆盖（只监听回环，禁 --host）。
# - 退出时只用 SIGTERM 停两个子进程（不用强制杀进程的信号，AD-14）。
# - 不碰本地数据目录、不清理构建、不装依赖、不写任何文件；日志直接输出到当前终端。
# - 兼容 macOS 自带 bash 3.2：不用 wait -n、mapfile、关联数组等 bash 4+ 语法。
# - brew PostgreSQL 退路：由调用方设环境变量 TODOAPP_LOCAL_DB_EMBEDDED=false（见 src/test/README.md）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- Node 版本检查（必须在启动任何进程之前，AD-14） ----
if ! command -v node >/dev/null 2>&1; then
    echo "dev.sh：找不到 node。需要 Node.js 24（见 todoapp-web/.nvmrc），请先安装或切换后重试。" >&2
    exit 1
fi
NODE_VERSION="$(node -v)"
NODE_MAJOR="${NODE_VERSION#v}"
NODE_MAJOR="${NODE_MAJOR%%.*}"
if [ "$NODE_MAJOR" != "24" ]; then
    echo "dev.sh：Node 主版本必须是 24，当前是 ${NODE_VERSION}。请按 todoapp-web/.nvmrc 切换到 24 后重试。" >&2
    exit 1
fi

BE_PID=""
FE_PID=""
STOPPED=0

# 用 SIGTERM 停掉两个子进程组并等待结束；幂等（INT/TERM 与 EXIT 都会触发，只执行一次）。
stop_all() {
    if [ "$STOPPED" -eq 1 ]; then
        return 0
    fi
    STOPPED=1
    # 清理期间忽略重复的中断，保证清理走完
    trap '' INT TERM
    local pid
    for pid in $BE_PID $FE_PID; do
        # 每个子进程跑在自己的进程组里（见下方 set -m）：对整个组发 SIGTERM，
        # 让 mvnw/npm 外壳之下真正监听端口的 java、vite 进程也收到信号。
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    done
    for pid in $BE_PID $FE_PID; do
        wait "$pid" 2>/dev/null || true
    done
    # 组长退出后组内进程（如 test-run 派生的应用 JVM）可能还在优雅关闭，最多再等 30 秒。
    local waited=0
    for pid in $BE_PID $FE_PID; do
        while kill -0 -- "-$pid" 2>/dev/null && [ "$waited" -lt 30 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 -- "-$pid" 2>/dev/null; then
            echo "dev.sh：进程组 $pid 在 SIGTERM 后 30 秒仍未退出，请检查并正常结束它。" >&2
        fi
    done
}

on_signal() {
    stop_all
    exit "$1"
}

trap 'on_signal 130' INT
trap 'on_signal 143' TERM
trap stop_all EXIT

# 子进程不读终端：后台进程组读 TTY 会被 SIGTTIN 挂起（vite 的快捷键监听会读 stdin）。
exec </dev/null

# 让两个后台子进程各自成为独立进程组（便于对整组发 SIGTERM）；启动后立刻关闭，
# 主循环里的 sleep 仍留在本脚本的进程组，Ctrl-C 由本脚本的 trap 统一处理。
set -m
"$SCRIPT_DIR/../mvnw" -f "$SCRIPT_DIR/../pom.xml" spring-boot:test-run &
BE_PID=$!
npm --prefix "$SCRIPT_DIR/../../todoapp-web" run dev &
FE_PID=$!
set +m

echo "dev.sh：后端 PID ${BE_PID}（http://127.0.0.1:8080），前端 PID ${FE_PID}（http://127.0.0.1:5173）。Ctrl-C 一起停。"

# bash 3.2 没有 wait -n：轮询，任一子进程自己退出（如端口被占）就停掉另一个并以非 0 退出。
while kill -0 "$BE_PID" 2>/dev/null && kill -0 "$FE_PID" 2>/dev/null; do
    sleep 1
done

if kill -0 "$BE_PID" 2>/dev/null; then
    echo "dev.sh：前端进程已退出（例如 5173 被占用），正在停止后端。" >&2
else
    echo "dev.sh：后端进程已退出（例如 8080 或 54329 被占用），正在停止前端。" >&2
fi
exit 1
