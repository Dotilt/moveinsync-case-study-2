@echo off
setlocal
set "MVN_BIN=%LOCALAPPDATA%\apache-maven\apache-maven-3.9.6\bin\mvn.cmd"
if exist "%MVN_BIN%" (
    call "%MVN_BIN%" %*
    exit /b %ERRORLEVEL%
) else (
    echo Error: Maven not found at %MVN_BIN%
    exit /b 1
)
