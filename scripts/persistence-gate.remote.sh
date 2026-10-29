#!/usr/bin/env bash
# persistence-gate.remote.sh —— 服务器侧持久化门禁执行器（在云服务器上运行）
#
# 两个口径：
#   mysql84 —— 生产口径：宿主 MySQL 8.4 上建临时库，从零跑 V1→V38（38 条），证明全新安装可行。
#   mysql57 —— 存量升级：Docker 起 mysql:5.7，用 V19 基线快照跑 V20→V38（19 条），
#              证明「已有 5.7 老库能升上来」。注意它不宣称 5.7 能全新安装（V18 用了窗口函数）。
#
# 安全红线（8.4 与生产库同实例，这是本脚本最大的风险点）：
#   1. 库名必须匹配下面两条正则之一，否则拒绝执行；
#   2. DROP 前再次校验正则，绝不用裸变量拼 DROP；
#   3. 8.4 走 sudo mysql（auth_socket）建库，另建低权限 TCP 用户跑 JDBC —— 不碰 root、不碰生产库；
#   4. 运行前后各确认一次生产库 intelligent_resume 仍在。
#
# 退出码语义（调用方据此判断，不要混用）：
#   0  绿（用例真实执行且通过）
#   1  红（测试失败，或检测到"用例被跳过"——那是未验证，不是通过）
#   3  未测量（镜像拉不到 / 容器起不来 / 版本不符 / 内存不足 / 缺工具）
#   75 并发占用（另一个门禁实例持有锁）
#
# 用法：
#   bash persistence-gate.remote.sh                 # 两个口径都跑
#   GATE=mysql84 bash persistence-gate.remote.sh    # 只跑 8.4
#   GATE=mysql57 MYSQL57_IMAGE=mysql:5.7 bash ...   # 换镜像 tag
#   PERSISTENCE_GATE_FORCE=1 bash ...               # 内存不足时仍强行执行

set -uo pipefail

GATE="${GATE:-both}"
SRC_DIR="${SRC_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/server}"
LOG_DIR="${LOG_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/logs}"
MYSQL57_IMAGE="${MYSQL57_IMAGE:-mysql:5.7.44}"
MYSQL57_PORT="${MYSQL57_PORT:-3307}"
CONTAINER="ir-mysql57-gate"
PROD_SCHEMA="intelligent_resume"
MEM_FLOOR_KB=$((800 * 1024))

mkdir -p "$LOG_DIR"
GATE_LOG="$LOG_DIR/persistence-gate-$(date +%Y%m%d-%H%M%S).log"

# 8.4 的临时库与用户（清理时要用，声明在顶层供 trap 读取）
SCHEMA84=""
USER84=""
NEED_CLEANUP84=0

log() { printf '[gate] %s\n' "$*" | tee -a "$GATE_LOG"; }
warn() { printf '[gate][warn] %s\n' "$*" | tee -a "$GATE_LOG" >&2; }
die_env() { printf 'GATE-ENV %s\n' "$*" | tee -a "$GATE_LOG" >&2; exit 3; }
die_red() { printf 'GATE-RED %s\n' "$*" | tee -a "$GATE_LOG" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------

check_tools() {
    for cmd in java mvn sudo openssl; do
        command -v "$cmd" >/dev/null 2>&1 || die_env "缺少命令：$cmd"
    done
    command -v python3 >/dev/null 2>&1 || die_env "缺少 python3（用于解析 surefire 报告）"
    command -v docker >/dev/null 2>&1 || die_env "缺少 docker"
}

check_memory() {
    local avail_kb
    avail_kb=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
    if [ "$avail_kb" -lt "$MEM_FLOOR_KB" ] && [ "${PERSISTENCE_GATE_FORCE:-0}" != "1" ]; then
        sudo docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}' 2>/dev/null | tee -a "$GATE_LOG" || true
        die_env "可用内存仅 $((avail_kb / 1024))MB（阈值 $((MEM_FLOOR_KB / 1024))MB），同机还有艺培通容器在跑。
         强行运行有 OOM 掉邻居的风险；确认无碍请设 PERSISTENCE_GATE_FORCE=1 重跑。"
    fi
    log "可用内存 $((avail_kb / 1024))MB（阈值 $((MEM_FLOOR_KB / 1024))MB）"
}

acquire_lock() {
    local lock_file="/tmp/persistence-gate.lock"
    exec 9>"$lock_file"
    if ! flock -w 900 9; then
        die_env "另一次持久化门禁正在运行（锁 $lock_file）；锁的目的不是容器名冲突，而是保护 1.7G 可用内存"
    fi
}

# 生产库护栏：运行前后各查一次
ensure_prod_schema_intact() {
    local phase="$1" count
    count=$(sudo mysql -N -B -e \
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$PROD_SCHEMA'" 2>/dev/null)
    if [ "$count" != "1" ]; then
        die_env "[$phase] 生产库 $PROD_SCHEMA 不存在（count=$count）—— 立即停止，不要再执行任何 DROP"
    fi
    log "[$phase] 生产库 $PROD_SCHEMA 在（未受影响）"
}

# ---------------------------------------------------------------------------
# surefire 校验：证明「真的跑了」而不是被 JUnit 跳过
# ---------------------------------------------------------------------------

# assert_surefire 早期版本走管道拿不到真实退出码，已并入 run_and_assert（此处保留注释说明为何不拆函数）
run_and_assert() {
    local it_class="$1" label="$2"
    local report="$SRC_DIR/target/surefire-reports/TEST-${it_class}.xml"
    local mvn_log="$LOG_DIR/mvn-${label}-$(date +%H%M%S).log"

    rm -rf "$SRC_DIR/target/surefire-reports"

    log "[$label] 执行 mvn -Dtest=$it_class"
    ( cd "$SRC_DIR" && MAVEN_OPTS="${MAVEN_OPTS:--Xmx320m}" \
        mvn -B --no-transfer-progress -Dtest="$it_class" \
            -DargLine="-Xmx384m" test ) > "$mvn_log" 2>&1
    local mvn_rc=$?

    python3 - "$report" "$mvn_rc" "$label" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, mvn_rc, label = sys.argv[1], int(sys.argv[2]), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except FileNotFoundError:
    print(f"GATE-RED [{label}] 找不到 surefire 报告 {path}（mvn rc={mvn_rc}）—— "
          f"该类根本没被执行：编译失败 / -Dtest 名字写错 / 构建在更早阶段中断")
    sys.exit(1)
except ET.ParseError as exc:
    print(f"GATE-RED [{label}] surefire 报告无法解析：{exc}")
    sys.exit(1)

tests = int(root.get("tests", "0"))
skipped = int(root.get("skipped", "0"))
failures = int(root.get("failures", "0"))
errors = int(root.get("errors", "0"))
print(f"[{label}] surefire: tests={tests} skipped={skipped} failures={failures} errors={errors} (mvn rc={mvn_rc})")

if tests == 0 or skipped >= tests:
    print(f"GATE-RED [{label}] 用例被 JUnit 跳过（环境变量门控未生效）—— "
          f"这是「未验证」，不是「通过」。mvn 退出码为 0 也不代表门禁承重。")
    sys.exit(1)
if failures or errors or mvn_rc != 0:
    print(f"GATE-RED [{label}] 门禁失败（详见 {path} 与 mvn 日志）")
    sys.exit(1)
print(f"GATE-GREEN [{label}] 用例真实执行且通过")
PY
    local verdict=$?
    if [ "$verdict" -ne 0 ]; then
        warn "[$label] mvn 日志：$mvn_log"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 口径一：MySQL 8.4 全新迁移（宿主实例，零容器）
# ---------------------------------------------------------------------------

run_mysql84() {
    log "===== 口径 1/2：MySQL 8.4 全新安装（V1→V38）====="

    local host_version
    host_version=$(sudo mysql -N -B -e 'SELECT VERSION()' 2>/dev/null)
    case "$host_version" in
        8.4.*) log "宿主 MySQL 版本 $host_version（生产口径）" ;;
        *) die_env "宿主 MySQL 版本是 '$host_version'，不是 8.4.x —— 拒绝用它冒充生产口径" ;;
    esac

    local suffix db_pass
    suffix=$(openssl rand -hex 8)
    SCHEMA84="intelligent_resume_gate84_$suffix"
    USER84="ir_g84_${suffix:0:12}"
    db_pass=$(openssl rand -hex 16)

    # 安全红线 1：库名/用户名必须严格合形，防止变量被污染后误伤生产库
    [[ "$SCHEMA84" =~ ^intelligent_resume_gate84_[a-f0-9]{16}$ ]] || die_env "生成的库名不合规：$SCHEMA84"
    [[ "$USER84" =~ ^ir_g84_[a-f0-9]{12}$ ]] || die_env "生成的用户名不合规：$USER84"

    log "临时库 $SCHEMA84，临时用户 $USER84（密码不打印）"
    ensure_prod_schema_intact "before"

    if ! sudo mysql --default-character-set=utf8mb4 >/dev/null 2>&1 <<SQL
CREATE DATABASE \`$SCHEMA84\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '$USER84'@'127.0.0.1' IDENTIFIED BY '$db_pass';
GRANT ALL PRIVILEGES ON \`$SCHEMA84\`.* TO '$USER84'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
    then
        die_env "建库/建用户失败（库=$SCHEMA84）"
    fi
    NEED_CLEANUP84=1

    export MYSQL84_LIVE_TEST=true
    export MYSQL84_JDBC_URL="jdbc:mysql://127.0.0.1:3306/${SCHEMA84}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=180000"
    export MYSQL84_USER="$USER84"
    export MYSQL84_PASSWORD="$db_pass"

    if ! run_and_assert "com.intelligentresume.database.MySql84FreshMigrationIT" "mysql84"; then
        log "口径 1/2 结果：红"
        return 1
    fi

    ensure_prod_schema_intact "after"
    log "口径 1/2 结果：绿"
    return 0
}

# ---------------------------------------------------------------------------
# 口径二：MySQL 5.7 存量升级（容器，V19→V38）
# ---------------------------------------------------------------------------

wait_mysql57_ready() {
    local root_pw="$1" i running
    for i in $(seq 1 60); do
        if docker exec "$CONTAINER" mysql -uroot -p"$root_pw" -N -B -e 'SELECT 1' >/dev/null 2>&1; then
            return 0
        fi
        # 容器初始化失败会退出，别傻等满 120 秒
        running=$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)
        if [ "$running" != "true" ]; then
            warn "5.7 容器在初始化阶段退出，末尾日志："
            docker logs --tail 50 "$CONTAINER" 2>&1 | tee -a "$GATE_LOG" || true
            return 1
        fi
        sleep 2
    done
    warn "5.7 容器 120 秒内未就绪，末尾日志："
    docker logs --tail 80 "$CONTAINER" 2>&1 | tee -a "$GATE_LOG" || true
    return 1
}

run_mysql57() {
    log "===== 口径 2/2：MySQL 5.7 存量升级（V19→V38）====="

    # 镜像：本地没有则拉，超时必须给，否则 ssh 会话会无限挂住
    if ! docker image inspect "$MYSQL57_IMAGE" >/dev/null 2>&1; then
        log "本地无 $MYSQL57_IMAGE，开始拉取（mirror 见 docker info）"
        if ! timeout 600 docker pull "$MYSQL57_IMAGE" 2>&1 | tail -3 | tee -a "$GATE_LOG"; then
            cat >&2 <<EOF
GATE-ENV 镜像拉取失败 —— 门禁**未执行**，结论未知（这不是红）。
可选处置：
  1) 重试一次（腾讯云 mirror 偶发超时，第二次常常成功）；
  2) 换 tag：MYSQL57_IMAGE=mysql:5.7 GATE=mysql57 bash scripts/persistence-gate.remote.sh
  3) 手工预拉：ssh <host> 'docker pull mysql:5.7.44'
  4) 若 mirror 不代理该仓库，临时直连 Docker Hub，用完改回（勿长期移除 mirror，会影响同机其他项目）。
EOF
            exit 3
        fi
    fi

    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true   # 清掉上次 kill -9 的残留，保证幂等

    local root_pw suffix db_pass db_user
    root_pw=$(openssl rand -hex 16)
    suffix=$(openssl rand -hex 8)
    local schema="intelligent_resume_gate_$suffix"
    db_user="ir_gate_${suffix:0:12}"
    db_pass=$(openssl rand -hex 16)

    [[ "$schema" =~ ^intelligent_resume_gate_[a-f0-9]{16}$ ]] || die_env "生成的库名不合规：$schema"
    [[ "$db_user" =~ ^ir_gate_[a-f0-9]{12}$ ]] || die_env "生成的用户名不合规：$db_user"

    log "启动容器 $CONTAINER（$MYSQL57_IMAGE，127.0.0.1:$MYSQL57_PORT，内存上限 512m）"
    if ! docker run -d --name "$CONTAINER" --restart=no \
            --memory=512m --memory-swap=512m \
            --label intelligent-resume.gate=mysql57 \
            -p "127.0.0.1:${MYSQL57_PORT}:3306" \
            -e MYSQL_ROOT_PASSWORD="$root_pw" \
            "$MYSQL57_IMAGE" \
            --character-set-server=utf8mb4 \
            --collation-server=utf8mb4_unicode_ci \
            --innodb-buffer-pool-size=128M \
            --performance-schema=OFF \
            --max-connections=30 \
            --skip-name-resolve >/dev/null 2>&1; then
        die_env "5.7 容器启动失败（镜像 $MYSQL57_IMAGE）"
    fi

    wait_mysql57_ready "$root_pw" || die_env "5.7 容器未就绪，门禁未执行"

    # 防镜像被 mirror 供成别的版本：版本错了就不是「5.7 存量升级」这个结论
    local container_version
    container_version=$(docker exec "$CONTAINER" mysql -uroot -p"$root_pw" -N -B -e 'SELECT VERSION()' 2>/dev/null)
    case "$container_version" in
        5.7.*) log "容器 MySQL 版本 $container_version" ;;
        *) die_env "镜像 $MYSQL57_IMAGE 报告版本 '$container_version'，不是 5.7.x —— 拒绝用它跑门禁" ;;
    esac

    # 库必须是 utf8mb4：V23 建表不写 CHARSET，继承库默认；latin1 会让中文种子在严格模式下直接失败
    if ! docker exec -i "$CONTAINER" mysql -uroot -p"$root_pw" --default-character-set=utf8mb4 >/dev/null 2>&1 <<SQL
CREATE DATABASE \`$schema\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '$db_user'@'%' IDENTIFIED BY '$db_pass';
GRANT ALL PRIVILEGES ON \`$schema\`.* TO '$db_user'@'%';
FLUSH PRIVILEGES;
SQL
    then
        die_env "5.7 建库/建用户失败（库=$schema）"
    fi

    export MYSQL57_LIVE_TEST=true
    export MYSQL57_JDBC_URL="jdbc:mysql://127.0.0.1:${MYSQL57_PORT}/${schema}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=180000"
    export MYSQL57_USER="$db_user"
    export MYSQL57_PASSWORD="$db_pass"

    if ! run_and_assert "com.intelligentresume.database.MySql57MigrationLiveIT" "mysql57"; then
        log "口径 2/2 结果：红"
        return 1
    fi

    log "口径 2/2 结果：绿"
    return 0
}

# ---------------------------------------------------------------------------
# 清理
# ---------------------------------------------------------------------------

cleanup() {
    local rc=$?
    trap - EXIT INT TERM

    if [ "${NEED_CLEANUP84:-0}" = "1" ]; then
        # 安全红线 2：DROP 前再校验一次库名形状，绝不用裸变量拼 DROP
        if [[ "$SCHEMA84" =~ ^intelligent_resume_gate84_[a-f0-9]{16}$ ]] \
           && [[ "$USER84" =~ ^ir_g84_[a-f0-9]{12}$ ]]; then
            sudo mysql >/dev/null 2>&1 <<SQL || true
DROP DATABASE IF EXISTS \`$SCHEMA84\`;
DROP USER IF EXISTS '$USER84'@'127.0.0.1';
SQL
            log "已清理临时库 $SCHEMA84 与用户 $USER84"
        else
            warn "库名/用户名形状异常，跳过 DROP 以免误删（库=$SCHEMA84，用户=$USER84）"
        fi
    fi

    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
        local ts
        ts=$(date +%Y%m%d-%H%M%S)
        docker logs "$CONTAINER" > "$LOG_DIR/mysql57-container-$ts.log" 2>&1 || true
        docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
        # 5.7 不需要 DROP DATABASE：整个实例随容器消失（与本机 ps1 的差异，不要照抄回来）
        log "已删除 5.7 容器（日志留在 $LOG_DIR/mysql57-container-$ts.log）"
    fi

    log "门禁结束，退出码 $rc；本次日志 $GATE_LOG"
    exit "$rc"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

[ -d "$SRC_DIR" ] || die_env "源码目录不存在：$SRC_DIR"
[ -f "$SRC_DIR/pom.xml" ] || die_env "$SRC_DIR 下没有 pom.xml（打包路径不对）"

log "源码目录 $SRC_DIR；门禁选择 GATE=$GATE；日志 $GATE_LOG"
check_tools
check_memory
acquire_lock

case "$GATE" in
    mysql84)  run_mysql84 ;;
    mysql57)  run_mysql57 ;;
    both)
        # 串行执行：两个 Maven fork 同时跑会在这台机器上打穿内存
        run_mysql84 || exit 1
        run_mysql57 || exit 1
        ;;
    *) die_env "未知的 GATE=$GATE（可选 mysql84|mysql57|both）" ;;
esac
