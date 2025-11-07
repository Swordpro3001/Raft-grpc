#!/bin/bash
# Script to stop the Raft cluster using saved PIDs
# Usage: ./stop-cluster.sh

PID_FILE=".cluster_pids"

echo "Stopping Raft Cluster..."
echo

if [ ! -f "$PID_FILE" ]; then
  echo "ERROR: PID file not found!"
  echo "Expected: $PID_FILE"
  echo
  read -p "Press Enter to exit..."
  exit 1
fi

echo "Reading PIDs from $PID_FILE..."

while IFS= read -r pid; do
  if [ -n "$pid" ]; then
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null
      echo "Stopped process PID: $pid"
    else
      echo "PID $pid not running."
    fi
  fi
done < "$PID_FILE"

echo
echo "Cluster stopped!"
echo

# Optionally, remove the PID file
rm -f "$PID_FILE"

read -p "Press Enter to exit..."
