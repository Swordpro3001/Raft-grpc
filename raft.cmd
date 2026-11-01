@echo off
setlocal
cd /d "%~dp0"

if "%~1"=="reset" goto reset
if "%~1"=="test" goto test
if "%~1"=="help" goto usage
if "%~1"=="" goto start
if "%~1"=="start" goto start
goto usage

:start
call "%~dp0gradlew.bat" -q --console=plain bootJar
if errorlevel 1 exit /b 1
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
set "NODES=%~2"
if "%NODES%"=="" set "NODES=5"
"%JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx256m -jar build\libs\raft.jar --raft.cluster-size=%NODES%
exit /b %errorlevel%

:reset
if exist data rmdir /s /q data
if exist logs rmdir /s /q logs
echo Removed data and logs.
exit /b 0

:test
call "%~dp0gradlew.bat" --console=plain test
exit /b %errorlevel%

:usage
echo Usage: raft [command]
echo.
echo   start [nodes]   Start the dashboard and a local cluster, 5 nodes by default (default command)
echo   reset           Delete all node data and logs
echo   test            Run the tests
exit /b 0
