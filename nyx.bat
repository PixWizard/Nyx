@echo off
setlocal
set "DIR=%~dp0"

if not exist "%DIR%target\nyx-stegano-1.0-SNAPSHOT.jar" (
    echo [*] Nyx binary not found. Building project...
    call mvn package dependency:copy-dependencies -DoutputDirectory=target/lib -DskipTests
    if errorlevel 1 exit /b %errorlevel%
) else if not exist "%DIR%target\lib" (
    echo [*] Dependencies not found. Packaging dependencies...
    call mvn dependency:copy-dependencies -DoutputDirectory=target/lib -DskipTests
    if errorlevel 1 exit /b %errorlevel%
)

java --module-path "%DIR%target\nyx-stegano-1.0-SNAPSHOT.jar;%DIR%target\lib" -m org.ghost/org.ghost.Main %*
