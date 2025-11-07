#!/bin/bash

echo "Starting Dashboard Client..."
./gradlew bootRun --args='--spring.profiles.active=client' &

echo
echo "Dashboard Client starting on http://localhost:8080"
echo
read -p "Press Enter to continue..."
