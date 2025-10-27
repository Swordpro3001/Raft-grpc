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
nohup ./gradlew bootRun --args='--spring.profiles.active=node1' > node1.log 2>&1 &
NODE1_PID=$!
echo $NODE1_PID >> .cluster_pids

sleep 3

echo "Starting Node 2 on port 8082 (gRPC: 9092)..."
nohup ./gradlew bootRun --args='--spring.profiles.active=node2' > node2.log 2>&1 &
NODE2_PID=$!
echo $NODE2_PID >> .cluster_pids

sleep 3

echo "Starting Node 3 on port 8083 (gRPC: 9093)..."
nohup ./gradlew bootRun --args='--spring.profiles.active=node3' > node3.log 2>&1 &
NODE3_PID=$!
echo $NODE3_PID >> .cluster_pids

sleep 3

echo "Starting Node 4 on port 8084 (gRPC: 9094)..."
nohup ./gradlew bootRun --args='--spring.profiles.active=node4' > node4.log 2>&1 &
NODE4_PID=$!
echo $NODE4_PID >> .cluster_pids

sleep 3

echo "Starting Node 5 on port 8085 (gRPC: 9095)..."
nohup ./gradlew bootRun --args='--spring.profiles.active=node5' > node5.log 2>&1 &
NODE5_PID=$!
echo $NODE5_PID >> .cluster_pids

sleep 5

echo ""
echo "All nodes started in background!"
echo "PIDs saved to .cluster_pids"
echo ""
echo "Dashboard: http://localhost:8081"
echo "Node 1: http://localhost:8081/api/status"
echo "Node 2: http://localhost:8082/api/status"
echo "Node 3: http://localhost:8083/api/status"
echo "Node 4: http://localhost:8084/api/status"
echo "Node 5: http://localhost:8085/api/status"
echo ""
echo "Logs:"
echo "  node1.log, node2.log, node3.log, node4.log, node5.log"
echo ""
echo "To stop all nodes, run: ./stop-cluster.sh"
