#!/bin/bash
# Self-contained Play 1 bundle launcher (written by `play bundle`).
#
# Mirrors the dev-time `play` shim's CLI surface for runtime commands —
# run / start / stop / restart / status / pid / out — plus `secret` for
# first-run setup. Dispatches to a direct java exec instead of gradle.
# No gradle, no internet, no framework download required at runtime;
# java 25+ is the only dependency.

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

FW_VERSION="__FW_VERSION__"
FW_JAR="framework/play-${FW_VERSION}.jar"
PID_FILE="${PLAY_PID_FILE:-server.pid}"
PLAY_ID="${PLAY_ID:-prod}"

CMD="${1:-help}"
[ $# -gt 0 ] && shift

# 64-char [A-Za-z0-9] secret from /dev/urandom.
generate_secret() {
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 64
}

cmd_secret() {
    local conf="conf/application.conf"
    [ -f "$conf" ] || { echo "play: $conf not found at $SCRIPT_DIR" >&2; exit 1; }

    # Extract VAR from `application.secret = ${VAR}` line. The framework
    # rejects literal secrets — only ${VAR} placeholders are accepted.
    # Default to PLAY_SECRET if conf doesn't pin a specific var.
    local var
    var=$(awk '
        /^[[:space:]]*application\.secret[[:space:]]*=[[:space:]]*\$\{[^}:]+\}[[:space:]]*$/ {
            match($0, /\$\{[^}]+\}/)
            print substr($0, RSTART+2, RLENGTH-3)
            exit
        }
    ' "$conf")
    [ -z "$var" ] && var="PLAY_SECRET"

    local secret
    secret=$(generate_secret)

    mkdir -p certs
    local envfile="certs/.env"
    if [ -f "$envfile" ] && grep -qE "^[[:space:]]*${var}[[:space:]]*=" "$envfile" 2>/dev/null; then
        local tmp="${envfile}.tmp"
        awk -v var="$var" -v val="$secret" '
            $0 ~ "^[[:space:]]*"var"[[:space:]]*=" { print var"="val; next }
            { print }
        ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
    else
        printf '%s=%s\n' "$var" "$secret" >> "$envfile"
    fi
    chmod 600 "$envfile"

    local example="certs/.env.example"
    if [ ! -f "$example" ]; then
        cat > "$example" <<EXAMPLE
# Environment variables for this Play application.
#
# Copy this file to \`certs/.env\` (which is gitignored) and fill in real values:
#     cp certs/.env.example certs/.env

$var=
EXAMPLE
    fi

    echo "~ $var written to $SCRIPT_DIR/$envfile"
    echo "~ Keep this value secret and consistent across all instances of your app."
}

# Forward dev-shim flag forms into JVM args + app args. Same translation
# table as framework/play; entries unrecognized here are silently dropped
# (Gradle-specific flags like --info / --stacktrace have no analog at
# runtime).
JVM_EXTRA=()
APP_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --%*)                                              PLAY_ID="${arg#--%}" ;;
        --http.port=*|--https.port=*)                      APP_ARGS+=("$arg") ;;
        --pid-file=*)                                      PID_FILE="${arg#--pid-file=}" ;;
        -X*|-D*|-XX:*|-Xlog:*|-javaagent:*|-agentlib:*)    JVM_EXTRA+=("$arg") ;;
        *)                                                 : ;;
    esac
done

# Load certs/.env into the environment if present. Matches the dev shim's
# semantics: PLAY_SECRET, CERT_KEY_PASSWORD, etc. live in a gitignored .env
# rather than committed config. `set -a` auto-exports each variable assigned
# by the sourced file.
if [ -f certs/.env ]; then
    set -a
    . ./certs/.env
    set +a
fi

# Build JAVA_CMD lazily inside run/start so config-mutation commands like
# `secret` don't require the framework jar / classpath to exist yet.
build_java_cmd() {
    [ -f "$FW_JAR" ]   || { echo "play: $FW_JAR not found (run from bundle root)" >&2; exit 1; }
    [ -f .classpath ]  || { echo "play: .classpath not found at $SCRIPT_DIR" >&2; exit 1; }
    CP=$(tr '\n' ':' < .classpath | sed 's/:$//')
    JAVA_CMD=(
        java
        --enable-native-access=ALL-UNNAMED
        -javaagent:"$FW_JAR"
        -Dapplication.path="$SCRIPT_DIR"
        -Dplay.id="$PLAY_ID"
        -Dplay.version="$FW_VERSION"
        -Dprecompiled=true
        -Dfile.encoding=utf-8
        "${JVM_EXTRA[@]}"
        -classpath "$CP"
        play.server.Server
        "${APP_ARGS[@]}"
    )
}

case "$CMD" in
    run)
        build_java_cmd
        exec "${JAVA_CMD[@]}"
        ;;
    start)
        if [ -f "$PID_FILE" ]; then
            existing=$(cat "$PID_FILE")
            if kill -0 "$existing" 2>/dev/null; then
                echo "~ Already started (pid: $existing). Stop it first or delete $PID_FILE." >&2
                exit 1
            fi
            echo "~ Removing pid file $PID_FILE for not running pid $existing"
            rm -f "$PID_FILE"
        fi
        build_java_cmd
        mkdir -p logs
        nohup "${JAVA_CMD[@]}" >> logs/system.out 2>&1 &
        echo $! > "$PID_FILE"
        echo "~ OK, $SCRIPT_DIR is started"
        echo "~ pid is $(cat "$PID_FILE")"
        echo "~ output is redirected to $SCRIPT_DIR/logs/system.out"
        ;;
    stop)
        if [ ! -f "$PID_FILE" ]; then
            echo "~ $SCRIPT_DIR is already stopped"
            exit 0
        fi
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            # Wait up to 10s for graceful shutdown, then SIGKILL.
            for _ in $(seq 1 100); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 0.1
            done
            kill -9 "$pid" 2>/dev/null || true
            echo "~ OK, $SCRIPT_DIR is stopped"
        else
            echo "~ Play was not running (pid $pid not found); removing stale pid file"
        fi
        rm -f "$PID_FILE"
        ;;
    restart)
        "$0" stop
        "$0" start "$@"
        ;;
    status)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "Running (pid: $(cat "$PID_FILE"))"
        else
            echo "Not running"
            exit 1
        fi
        ;;
    pid)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            cat "$PID_FILE"
        else
            echo "~ The application is not running"
            exit 1
        fi
        ;;
    out)
        exec tail -f logs/system.out
        ;;
    secret)
        cmd_secret
        ;;
    help|--help|-h|"")
        cat <<EOF
Usage: ./play <command> [args]

Runtime commands:
  run                    Run the application in foreground
  start                  Start in background (writes pid file)
  stop                   Stop the running application
  restart                Restart the application
  status                 Print whether the application is running
  pid                    Print the pid of the running application
  out                    Tail logs/system.out

Setup commands:
  secret                 Generate application secret -> certs/.env

Argument forwarding (same shape as the dev-time \`play\` shim):
  --%<id>                Set play.id (default: prod)
  --http.port=X          HTTP listen port
  --https.port=X         HTTPS listen port
  --pid-file=<path>      Override pid file (default: server.pid)
  -X*, -D*, -XX:*, ...   JVM tuning forwarded to the spawned JVM

Environment:
  PLAY_ID                Default play.id (overridden by --%<id>)
  PLAY_PID_FILE          Default pid file path

This bundle is self-contained: java 25+ is the only runtime dependency.
EOF
        ;;
    *)
        echo "play: unknown command '$CMD'. Run './play help' for usage." >&2
        exit 1
        ;;
esac
