@echo off
echo Testing Management Node Setup...
echo.

echo Building project...
call gradlew.bat clean build -x test
if errorlevel 1 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Starting Management Node...
start "Management Node" cmd /c "gradlew.bat bootRun --args=--spring.profiles.active=management"

timeout /t 10 /nobreak >nul

echo.
echo Testing Management API...
echo.

echo 1. Health Check:
curl -s http://localhost:8080/api/management/health
echo.
echo.

echo 2. Cluster Overview (should show 0 healthy nodes - Raft nodes not started):
curl -s http://localhost:8080/api/management/cluster/overview
echo.
echo.

echo 3. All Nodes Status:
curl -s http://localhost:8080/api/management/nodes
echo.
echo.

echo.
echo Management Node is running!
echo Dashboard: http://localhost:8080
echo.
echo Press any key to stop the management node...
pause >nul

echo.
echo Stopping management node...
taskkill /FI "WINDOWTITLE eq Management Node*" /F >nul 2>&1

echo Done!
