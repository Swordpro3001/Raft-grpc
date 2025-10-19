@echo off
echo Starting Raft Cluster...
echo.

echo Building the project...
call gradlew.bat clean build -x test
if errorlevel 1 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Starting Node 1 on port 8081 (gRPC: 9091)...
start "Raft-Node1" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=node1'"

timeout /t 3 /nobreak >nul

echo Starting Node 2 on port 8082 (gRPC: 9092)...
start "Raft-Node2" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=node2'"

timeout /t 3 /nobreak >nul

echo Starting Node 3 on port 8083 (gRPC: 9093)...
start "Raft-Node3" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=node3'"

timeout /t 3 /nobreak >nul

echo Starting Node 4 on port 8084 (gRPC: 9094)...
start "Raft-Node4" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=node4'"

timeout /t 3 /nobreak >nul

echo Starting Node 5 on port 8085 (gRPC: 9095)...
start "Raft-Node5" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=node5'"

timeout /t 5 /nobreak >nul

echo.
echo All nodes started!
echo Opening dashboard in browser...
start "" "index.html"

echo.
echo Dashboard: http://localhost:8081 (or open index.html)
echo Node 1: http://localhost:8081/api/status
echo Node 2: http://localhost:8082/api/status
echo Node 3: http://localhost:8083/api/status
echo Node 4: http://localhost:8084/api/status
echo Node 5: http://localhost:8085/api/status
echo.
echo Press any key to exit (nodes will keep running)...
pause >nul
