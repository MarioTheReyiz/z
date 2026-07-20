@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "MAVEN=%ROOT%\apache-maven-3.8.6\bin\mvn.cmd"
set "OUT_DIR=C:\pewa"

echo [*] Building Java JAR...

if not exist "%MAVEN%" (
    echo [X] Maven bulunamadi: %MAVEN%
    pause
    exit /b 1
)

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

pushd "%ROOT%\java"
call "%MAVEN%" clean package -q
set "RESULT=%ERRORLEVEL%"
popd

if not "%RESULT%"=="0" (
    echo [X] Java build basarisiz.
    pause
    exit /b 1
)

if not exist "%OUT_DIR%\client.jar" (
    echo [X] client.jar olusturulamadi.
    pause
    exit /b 1
)

copy /y "%OUT_DIR%\client.jar" "%OUT_DIR%\pewa.jar" >nul
echo [OK] JAR hazir: %OUT_DIR%\client.jar
