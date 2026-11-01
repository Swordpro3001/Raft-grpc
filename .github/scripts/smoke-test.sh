#!/usr/bin/env bash
# Runs the packaged application like a user would and checks what unit tests cannot: the jar
# contains its templates and libraries, nodes fail over, and data survives a crash.
#
# Usage: smoke-test.sh [path/to/raft.jar]
set -euo pipefail

jar_path="${1:-build/libs/raft.jar}"
jar="$(cd "$(dirname "$jar_path")" && pwd)/$(basename "$jar_path")"
[ -f "$jar" ] || { echo "No jar at $jar"; exit 1; }

work="$(mktemp -d)"
cd "$work"

api="http://127.0.0.1:8080"
node_ports=(8081 8082 8083)
pid=""

# Kills the supervisor only. Its nodes have to notice that by themselves.
# In Git Bash, $! is a stand-in process for java, so there the port identifies the real one.
force_kill() {
    if [ -r "/proc/$1/winpid" ]; then
        local native
        native="$(netstat -ano | tr -d '\r' | awk '$2 ~ /:8080$/ && $3 ~ /:0$/ { print $5; exit }')"
        taskkill //F //PID "$native" > /dev/null
    else
        kill -9 "$1"
    fi
}

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ]; then
        echo "--- dashboard output"
        tail -n 30 dashboard.log 2>/dev/null || true
        for log in logs/*.log; do
            [ -f "$log" ] || continue
            echo "--- $log"
            tail -n 30 "$log" || true
        done
    fi
    if [ -n "$pid" ]; then
        force_kill "$pid" 2>/dev/null || true
        sleep 5
    fi
    cd /
    rm -rf "$work" 2>/dev/null || true
    exit "$status"
}
trap cleanup EXIT

wait_for() {
    local description="$1" timeout="$2"
    shift 2
    local deadline=$((SECONDS + timeout))
    until "$@"; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            echo "FAILED: timed out after ${timeout}s waiting for $description"
            exit 1
        fi
        sleep 0.5
    done
    echo "ok: $description"
}

check() {
    local description="$1"
    shift
    if ! "$@"; then
        echo "FAILED: $description"
        exit 1
    fi
    echo "ok: $description"
}

# The response is read completely first: with pipefail, curl | grep -q fails when grep stops early
response_contains() {
    local pattern="$1" response
    shift
    response="$(curl -fsS -m 15 "$@" 2>/dev/null)" || return 1
    grep -q -- "$pattern" <<< "$response"
}

start_cluster() {
    java -jar "$jar" --raft.cluster-size=3 >> dashboard.log 2>&1 &
    pid=$!
}

crash_cluster() {
    force_kill "$pid"
    wait "$pid" 2>/dev/null || true
    pid=""
}

current_leader() {
    local response
    response="$(curl -fsS -m 5 "$api/api/cluster/overview" 2>/dev/null)" || return 0
    grep -o '"leader":"node[0-9]*"' <<< "$response" | grep -o 'node[0-9]*' || true
}

has_leader() {
    [ -n "$(current_leader)" ]
}

leader_is_not() {
    local leader
    leader="$(current_leader)"
    [ -n "$leader" ] && [ "$leader" != "$1" ]
}

send() {
    response_contains '"COMMITTED"' -X POST "$api/api/command" \
        -H 'Content-Type: application/json' -d "{\"command\":\"$1\"}"
}

post_succeeds() {
    response_contains '"success":true' -X POST "$api$1"
}

on_all_nodes() {
    local port
    for port in "${node_ports[@]}"; do
        response_contains "\"$1\"" "http://127.0.0.1:$port/api/status" || return 1
    done
}

ports_closed() {
    local port
    for port in 8080 "${node_ports[@]}"; do
        if curl -s -m 1 -o /dev/null "http://127.0.0.1:$port/"; then
            return 1
        fi
    done
}

page_is_complete() {
    response_contains 'id="cluster-graph"' "$api/"
}

live_update_is_partial() {
    local body
    body="$(curl -fsS -m 15 -H 'HX-Request: true' "$api/")" || return 1
    grep -q 'id="state"' <<< "$body" && ! grep -q '<!DOCTYPE' <<< "$body"
}

assets_are_served() {
    local path
    for path in css/app.css js/cluster-graph.js webjars/htmx.org/dist/htmx.min.js \
                webjars/idiomorph/dist/idiomorph-ext.min.js webjars/cytoscape/dist/cytoscape.min.js; do
        curl -fsS -m 15 -o /dev/null "$api/$path" || return 1
    done
}

cross_site_post_is_refused() {
    [ "$(curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST -H 'Sec-Fetch-Site: cross-site' \
        "$api/actions/nodes/node1/stop")" = "403" ]
}

read_contains() {
    response_contains "\"$1\"" "$api/api/read"
}

check "ports are free (stop any running cluster first)" ports_closed

echo "Starting cluster from $jar"
start_cluster
wait_for "first leader" 180 has_leader

check "dashboard page renders" page_is_complete
check "live updates only return the changing part" live_update_is_partial
check "stylesheet, script and libraries are served" assets_are_served
check "cross-site form posts are refused" cross_site_post_is_refused

wait_for "command to commit" 30 send smoke-1
wait_for "command to reach all nodes" 30 on_all_nodes smoke-1
check "linearizable read sees the command" read_contains smoke-1

old_leader="$(current_leader)"
check "stopping leader $old_leader" post_succeeds "/api/nodes/$old_leader/stop"
wait_for "a new leader" 60 leader_is_not "$old_leader"
wait_for "command to commit without the old leader" 30 send smoke-2
check "restarting $old_leader" post_succeeds "/api/nodes/$old_leader/start"
wait_for "restarted node to catch up" 120 on_all_nodes smoke-2

check "creating a snapshot" post_succeeds "/api/snapshot/create"
wait_for "command to commit after the snapshot" 30 send smoke-3
wait_for "that command to reach all nodes" 30 on_all_nodes smoke-3

crash_cluster
wait_for "all processes to exit after killing the supervisor" 60 ports_closed

start_cluster
wait_for "leader after restart" 180 has_leader
wait_for "nodes to recover from snapshot and log" 60 on_all_nodes smoke-1
wait_for "nodes to recover entries after the snapshot" 60 on_all_nodes smoke-3
check "read after recovery" read_contains smoke-3

echo "Smoke test passed"
