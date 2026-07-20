@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "OUT_DIR=C:\pewa"

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAVEN=%ROOT%\apache-maven-3.8.6\bin\mvn.cmd"

echo ========================================
echo           PEWA FULL BUILDER
echo ========================================
echo.

echo [1/3] Preparing output directory...
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
echo [OK] Output: %OUT_DIR%
echo.

echo [2/3] Building Java client...
if not exist "%MAVEN%" (
    echo [X] Maven not found: %MAVEN%
    pause & exit /b 1
)
pushd "%ROOT%\java"
call "%MAVEN%" clean package
set "JAVA_RESULT=%ERRORLEVEL%"
popd
if not "%JAVA_RESULT%"=="0" (
    echo [X] Java build failed.
    pause & exit /b 1
)
if not exist "%OUT_DIR%\client.jar" (
    echo [X] client.jar not found after build.
    pause & exit /b 1
)
copy /y "%OUT_DIR%\client.jar" "%OUT_DIR%\pewa.jar" >nul
echo [OK] Java built: %OUT_DIR%\client.jar
echo.

echo [3/3] Building C++ DLL (cl.exe)...
powershell -ExecutionPolicy Bypass -NoProfile -File "%ROOT%\build_cpp.ps1"
if errorlevel 1 (
    echo [X] C++ build failed.
    pause & exit /b 1
)
if not exist "%OUT_DIR%\Pewa.dll" (
    echo [X] Pewa.dll not found after build.
    pause & exit /b 1
)
echo [OK] C++ built: %OUT_DIR%\Pewa.dll
echo.

echo ========================================
echo           BUILD COMPLETE
echo ========================================
echo Java: %OUT_DIR%\client.jar
echo DLL : %OUT_DIR%\Pewa.dll
echo.
pause
