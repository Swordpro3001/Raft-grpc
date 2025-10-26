@echo off
echo Stopping Raft Cluster...

echo Killing processes on ports 8081-8085...

taskkill /F /IM java.exe >nul 2>&1

echo.
echo Cluster stopped!
pause
