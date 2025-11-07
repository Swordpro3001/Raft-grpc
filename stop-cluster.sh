#!/bin/bash

echo "Stopping Raft Cluster..."
echo

echo "Killing processes on ports 8081–8085..."

pkill -f 'java' > /dev/null 2>&1

echo
echo "Cluster stopped!"
echo
read -p "Press Enter to exit..."
