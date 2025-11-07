@echo off
echo Starting Dashboard Client...
start "Dashboard Client" cmd /c "gradlew.bat bootRun --args=--spring.profiles.active=client"
echo.
echo Dashboard Client starting on http://localhost:8080
echo.
pause
