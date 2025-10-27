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

echo Starting Nodes silently in background...

rem === Node 1 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node1' -WindowStyle Hidden"

rem === Node 2 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node2' -WindowStyle Hidden"

rem === Node 3 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node3' -WindowStyle Hidden"

rem === Node 4 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node4' -WindowStyle Hidden"

rem === Node 5 ===
powershell -WindowStyle Hidden -Command "Start-Process 'cmd.exe' -ArgumentList '/c gradlew.bat bootRun --args=--spring.profiles.active=node5' -WindowStyle Hidden"

timeout /t 5 /nobreak >nul

echo.
echo All nodes started in background!

echo.
echo Dashboard: http://localhost:8081
echo Node 1: http://localhost:8081/api/status
echo Node 2: http://localhost:8082/api/status
echo Node 3: http://localhost:8083/api/status
echo Node 4: http://localhost:8084/api/status
echo Node 5: http://localhost:8085/api/status
echo.
echo Press any key to exit (nodes will keep running)...
pause >nul
