#!/bin/bash
# Script to start a new Raft node
# Usage: ./start-node.sh <node-id> <http-port> <grpc-port>
# Example: ./start-node.sh node6 8086 50056

NODE_ID=$1
HTTP_PORT=$2
GRPC_PORT=$3

# --- Argument validation ---
if [ -z "$NODE_ID" ] || [ -z "$HTTP_PORT" ] || [ -z "$GRPC_PORT" ]; then
  echo "Usage: $0 <node-id> <http-port> <grpc-port>"
  echo "Example: $0 node6 8086 50056"
  echo
  echo "Make sure you have created src/main/resources/application-${NODE_ID}.properties first!"
  read -p "Press Enter to exit..."
  exit 1
fi

echo
echo "========================================"
echo "Starting Raft Node"
echo "========================================"
echo "Node ID:    $NODE_ID"
echo "HTTP Port:  $HTTP_PORT"
echo "gRPC Port:  $GRPC_PORT"
echo "========================================"
echo

# --- Check if properties file exists ---
if [ ! -f "src/main/resources/application-${NODE_ID}.properties" ]; then
  echo "ERROR: Configuration file not found!"
  echo "Expected: src/main/resources/application-${NODE_ID}.properties"
  echo
  echo "Please create this file first. See ADD_NODE_INSTRUCTIONS.md for details."
  read -p "Press Enter to exit..."
  exit 1
fi

# --- Start the node in background ---
echo "Starting node in background..."
nohup ./gradlew bootRun --args="--spring.profiles.active=${NODE_ID}" > "logs/${NODE_ID}.log" 2>&1 &

sleep 3

echo
echo "Node started!"
echo
echo "Node Status:  http://localhost:${HTTP_PORT}/api/status"
echo "Dashboard:    http://localhost:8081"
echo
read -p "Press Enter to exit (node will keep running)..."
