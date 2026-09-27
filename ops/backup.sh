#!/usr/bin/env bash
#
# MAA 数据库备份（在服务器上跑，默认 /opt/maa/ops/backup.sh）
#
# 为什么不用 cp knowledge.mv.db：H2 是多页存储，运行中复制文件可能拿到撕裂副本 ——
# 当场看不出问题，等真要拿它恢复时才发现打不开。所以默认走应用内的在线备份（H2 BACKUP TO，
# 页级一致、允许并发写，实测 500KB 的库不到 100ms）。
#
# 用法：
#   ./backup.sh                # 在线备份（需要 /opt/maa/.env 里有 MAA_OPS_TOKEN）
#   ./backup.sh --cold         # 冷备份：停容器 → 复制文件 → 起容器（不依赖 token，代价是秒级停机）
#   TAG=20260927-0400 ./backup.sh    # 指定备份目录名（默认取当前时间）
#
# crontab 例子（每天 04:00）：
#   0 4 * * * /opt/maa/ops/backup.sh >> /var/log/maa-backup.log 2>&1
#
# 依赖：curl（在线模式）、docker compose（冷备模式）、python3（可选，用来校验 zip 完整性）
# 保留策略：默认留 14 天、最多 30 份，可用 KEEP_DAYS / KEEP_MAX 覆盖
#
set -euo pipefail

MAA_DIR="${MAA_DIR:-/opt/maa}"
BACKUP_DIR="${BACKUP_DIR:-$MAA_DIR/maa_db/backups}"
APP_URL="${APP_URL:-http://127.0.0.1:8080}"
KEEP_DAYS="${KEEP_DAYS:-14}"     # 按天数保留
KEEP_MAX="${KEEP_MAX:-30}"       # 再按份数兜一层，防止一天跑很多次把磁盘塞满
TAG="${TAG:-$(date +%Y%m%d-%H%M%S)}"
MODE="online"

if [ "${1:-}" = "--cold" ]; then MODE="cold"; fi
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    # 打印文件头部的注释块（# 开头的连续行），不写死行号：改了注释也不会把帮助切歪
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0
fi

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# 只备份"不可再生"的那几份：knowledge.mv.db 是用户/密码/对话；三份 json 是规则与别名。
# modrinth_cache.mv.db（几十 MB）是纯缓存，丢了只是重新抓一次，不备。
JSON_FILES=(rules.json aliases.json loader-versions.json)

case "$BACKUP_DIR" in
    */maa_db/backups) ;;
    *) echo "❌ 拒绝执行：BACKUP_DIR 必须是 maa_db/backups 下（当前：$BACKUP_DIR），防止清理策略误删别的东西"; exit 1 ;;
esac

if [ ! -d "$MAA_DIR/maa_db" ]; then
    echo "❌ 找不到 $MAA_DIR/maa_db —— 先确认 MAA_DIR 对不对（当前：$MAA_DIR）"; exit 1
fi

DEST="$BACKUP_DIR/$TAG"
mkdir -p "$DEST"

if [ "$MODE" = "online" ]; then
    # ---------- 在线备份：让应用自己出快照，不打断任何人的构筑 ----------
    TOKEN=""
    if [ -f "$MAA_DIR/.env" ]; then
        # 重复行必须拒绝：脚本取第一行，而 Docker 解析 env_file 时重复键通常是"后者胜"，
        # 两边一旦取到不同的值就是 403，而且报错信息会指向"token 不对"，很难查。不猜，直接让人清干净。
        TOKEN_LINES="$(grep -c '^MAA_OPS_TOKEN=' "$MAA_DIR/.env" 2>/dev/null || true)"
        if [ "${TOKEN_LINES:-0}" -gt 1 ]; then
            echo "❌ $MAA_DIR/.env 里有 ${TOKEN_LINES} 行 MAA_OPS_TOKEN，无法确定服务端实际用的是哪一行。"
            echo "   请删到只剩一行，然后 docker compose up -d 再重跑。"
            exit 1
        fi
        # 兼容三种写法：MAA_OPS_TOKEN=abc / MAA_OPS_TOKEN="abc" / MAA_OPS_TOKEN='abc'（外加 CRLF 的 \r）
        TOKEN="$(grep -m1 '^MAA_OPS_TOKEN=' "$MAA_DIR/.env" | cut -d= -f2- | tr -d '\r' \
                 | tr -d '"' | tr -d "'" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)"
    fi
    if [ -z "$TOKEN" ]; then
        echo "❌ .env 里没有 MAA_OPS_TOKEN，在线备份不可用。"
        echo "   要么在 /opt/maa/.env 里加一行 MAA_OPS_TOKEN=\$(openssl rand -hex 24) 后 docker compose up -d；"
        echo "   要么改用冷备份：$0 --cold"
        exit 1
    fi
    # 放在 token 校验之后：配置问题先说清楚，别让"没装 curl"把配置错误的提示盖掉
    if ! command -v curl >/dev/null 2>&1; then
        echo "❌ 这台机器没有 curl，在线备份发不出请求（apt install -y curl），或改用冷备份：$0 --cold"; exit 1
    fi

    BODY="$(mktemp)"
    HTTP="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST "$APP_URL/api/ops/backup" \
            -H "X-Ops-Token: $TOKEN" -H 'Content-Type: application/json' \
            -d "{\"tag\":\"$TAG\"}" || true)"
    if [ "$HTTP" != "200" ]; then
        echo "❌ 在线备份失败（HTTP ${HTTP:-无响应}）：$(cat "$BODY" 2>/dev/null)"
        echo "   应用没起来？端口不对？或 token 与服务端不一致？冷备份可用：$0 --cold"
        rm -f "$BODY"; exit 1
    fi
    log "✅ 在线备份完成：$(cat "$BODY")"
    rm -f "$BODY"

    if [ ! -s "$DEST/knowledge.zip" ]; then
        echo "❌ 接口说成功，但 $DEST/knowledge.zip 不存在或是空的 —— 不能当这次备份成功"; exit 1
    fi

    for f in "${JSON_FILES[@]}"; do
        [ -f "$MAA_DIR/maa_db/$f" ] && cp -p "$MAA_DIR/maa_db/$f" "$DEST/$f" || true
    done
else
    # ---------- 冷备份：不依赖 token，代价是停容器这几秒 ----------
    cd "$MAA_DIR"
    # 无论中途出什么错，都要把容器拉起来（否则一次备份失败 = 站点一直挂着）
    trap 'docker compose up -d >/dev/null 2>&1 || true' EXIT
    if [ ! -f "$MAA_DIR/maa_db/knowledge.mv.db" ]; then
        echo "❌ 找不到 $MAA_DIR/maa_db/knowledge.mv.db，冷备份中止（容器会照常恢复）"; exit 1
    fi
    log "⏸  冷备份：停容器（站点会短暂不可用）"
    docker compose stop
    cp -p "$MAA_DIR/maa_db/knowledge.mv.db" "$DEST/knowledge.mv.db"
    for f in "${JSON_FILES[@]}"; do
        [ -f "$MAA_DIR/maa_db/$f" ] && cp -p "$MAA_DIR/maa_db/$f" "$DEST/$f" || true
    done
    docker compose up -d
    trap - EXIT
    log "▶️  冷备份完成，容器已恢复"
fi

# ---------- 校验：备份不做校验等于没备份 ----------
if command -v python3 >/dev/null 2>&1 && [ -f "$DEST/knowledge.zip" ]; then
    if python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); sys.exit(1 if z.testzip() else 0)" "$DEST/knowledge.zip"; then
        log "🔍 zip 校验通过"
    else
        echo "❌ $DEST/knowledge.zip 校验失败（文件损坏），请重跑一次备份"; exit 1
    fi
fi

# ---------- 保留策略 ----------
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime "+$KEEP_DAYS" -exec rm -rf -- {} + 2>/dev/null || true
if [ "$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l)" -gt "$KEEP_MAX" ]; then
    find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | tail -n "+$((KEEP_MAX + 1))" | cut -d' ' -f2- \
        | xargs -r rm -rf --
fi

KEPT="$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l)"
SIZE="$(du -sh "$DEST" | cut -f1)"
DISK="$(df -h "$BACKUP_DIR" | awk 'NR==2 {print $4" 可用("$5" 已用)"}')"
log "📦 本次产出一份备份：$DEST（$SIZE）"
log "🗂  目录里共 $KEPT 份备份（保留 ${KEEP_DAYS} 天 / 最多 ${KEEP_MAX} 份）；磁盘：$DISK"
