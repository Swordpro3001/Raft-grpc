#!/bin/sh
set -e
cd "$(dirname "$0")"

case "${1:-start}" in
  start)
    ./gradlew -q --console=plain bootJar
    exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx256m \
      -jar build/libs/raft.jar --raft.cluster-size="${2:-5}"
    ;;
  reset)
    rm -rf data logs
    echo "Removed data and logs."
    ;;
  test)
    exec ./gradlew --console=plain test
    ;;
  *)
    echo "Usage: ./raft.sh [command]"
    echo
    echo "  start [nodes]   Start the dashboard and a local cluster, 5 nodes by default (default command)"
    echo "  reset           Delete all node data and logs"
    echo "  test            Run the tests"
    ;;
esac
