@echo off
echo Compiling Pewa via Maven...
call ..\apache-maven-3.8.6\bin\mvn.cmd clean package
if %errorlevel% neq 0 (
    echo Compilation FAILED!
    pause
    exit /b 1
)
echo Compilation SUCCESS!
pause
