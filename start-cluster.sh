#!/bin/bash

echo "Starting Raft Cluster..."
echo ""

echo "Building the project..."
./gradlew clean build -x test

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Starting Node 1 on port 8081 (gRPC: 9091)..."
./gradlew bootRun --args='--spring.profiles.active=node1' &
NODE1_PID=$!

sleep 3

echo "Starting Node 2 on port 8082 (gRPC: 9092)..."
./gradlew bootRun --args='--spring.profiles.active=node2' &
NODE2_PID=$!

sleep 3

echo "Starting Node 3 on port 8083 (gRPC: 9093)..."
./gradlew bootRun --args='--spring.profiles.active=node3' &
NODE3_PID=$!

sleep 3

echo "Starting Node 4 on port 8084 (gRPC: 9094)..."
./gradlew bootRun --args='--spring.profiles.active=node4' &
NODE4_PID=$!

sleep 3

echo "Starting Node 5 on port 8085 (gRPC: 9095)..."
./gradlew bootRun --args='--spring.profiles.active=node5' &
NODE5_PID=$!

sleep 5

echo ""
echo "All nodes started!"
echo "Opening dashboard..."
xdg-open index.html 2>/dev/null || open index.html 2>/dev/null || echo "Please open index.html manually"

echo ""
echo "Dashboard: file://$(pwd)/index.html"
echo "Node 1: http://localhost:8081/api/status"
echo "Node 2: http://localhost:8082/api/status"
echo "Node 3: http://localhost:8083/api/status"
echo "Node 4: http://localhost:8084/api/status"
echo "Node 5: http://localhost:8085/api/status"
echo ""
echo "Press Ctrl+C to stop all nodes..."

# Wait for Ctrl+C
trap "kill $NODE1_PID $NODE2_PID $NODE3_PID 2>/dev/null; exit" INT TERM
wait
