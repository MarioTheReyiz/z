@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "ROOT_DIR=%SCRIPT_DIR%\.."
set "MAVEN=%ROOT_DIR%\apache-maven-3.8.6\bin\mvn.cmd"
set "OUT_DIR=C:\pewa"

echo ========================================
echo           PEWA CLIENT BUILDER (MAVEN)
echo ========================================

echo [1/2] Running Maven build...
if not exist "%MAVEN%" (
    echo [X] Maven not found: %MAVEN%
    pause
    exit /b 1
)

pushd "%SCRIPT_DIR%"
call "%MAVEN%" clean package
set "BUILD_RESULT=%ERRORLEVEL%"
popd

if not "%BUILD_RESULT%"=="0" (
    echo [X] Maven compilation failed!
    pause
    exit /b 1
)

echo [2/2] Verifying output files...
if exist "%OUT_DIR%\client.jar" (
    echo [OK] client.jar generated at %OUT_DIR%\client.jar
    copy /y "%OUT_DIR%\client.jar" "%OUT_DIR%\pewa.jar" >nul
    echo [OK] Created backup at %OUT_DIR%\pewa.jar
) else (
    echo [X] %OUT_DIR%\client.jar not found!
    pause
    exit /b 1
)

echo.
echo ========================================
echo           BUILD COMPLETE!
echo ========================================
echo.
pause
