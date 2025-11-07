@echo off
REM Script to start a new Raft node
REM Usage: start-node.bat <node-id> <http-port> <grpc-port>
REM Example: start-node.bat node6 8086 50056

set NODE_ID=%1
set HTTP_PORT=%2
set GRPC_PORT=%3

if "%NODE_ID%"=="" (
    echo Usage: start-node.bat ^<node-id^> ^<http-port^> ^<grpc-port^>
    echo Example: start-node.bat node6 8086 50056
    echo.
    echo Make sure you have created src/main/resources/application-%NODE_ID%.properties first!
    pause
    exit /b 1
)

if "%HTTP_PORT%"=="" (
    echo Error: HTTP port not specified
    echo Usage: start-node.bat ^<node-id^> ^<http-port^> ^<grpc-port^>
    pause
    exit /b 1
)

if "%GRPC_PORT%"=="" (
    echo Error: gRPC port not specified
    echo Usage: start-node.bat ^<node-id^> ^<http-port^> ^<grpc-port^>
    pause
    exit /b 1
)

echo.
echo ========================================
echo Starting Raft Node
echo ========================================
echo Node ID:    %NODE_ID%
echo HTTP Port:  %HTTP_PORT%
echo gRPC Port:  %GRPC_PORT%
echo ========================================
echo.

REM Check if properties file exists
if not exist "src\main\resources\application-%NODE_ID%.properties" (
    echo ERROR: Configuration file not found!
    echo Expected: src\main\resources\application-%NODE_ID%.properties
    echo.
    echo Please create this file first. See ADD_NODE_INSTRUCTIONS.md for details.
    pause
    exit /b 1
)

echo Starting node in background...
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=%NODE_ID%' -WindowStyle Hidden"

timeout /t 3 /nobreak >nul

echo.
echo Node started!
echo.
echo Node Status:  http://localhost:%HTTP_PORT%/api/status
echo Dashboard:    http://localhost:8081
echo.
echo Press any key to exit (node will keep running)...
pause >nul
