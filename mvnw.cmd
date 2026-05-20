@echo off
setlocal

set "BASEDIR=%~dp0"
set "MAVEN_VERSION=3.9.9"
set "WRAPPER_DIR=%BASEDIR%.mvn\wrapper"
set "DIST_DIR=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%"
set "MVN=%DIST_DIR%\bin\mvn.cmd"
set "ZIP=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip"
set "URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MVN%" (
  if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
  if not exist "%ZIP%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%URL%' -OutFile '%ZIP%' -ErrorAction Stop"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%WRAPPER_DIR%' -Force"
  if errorlevel 1 exit /b 1
)

call "%MVN%" -Dmaven.repo.local="%BASEDIR%.mvn\repository" %*
exit /b %ERRORLEVEL%
endlocal
