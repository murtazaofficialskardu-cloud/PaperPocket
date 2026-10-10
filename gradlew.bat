@echo off
setlocal
set "APP_ROOT=%~dp0"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "GRADLE_EXEC=%GRADLE_USER_HOME%\paperpocket-distributions\gradle-8.13\bin\gradle.bat"
if exist "%GRADLE_EXEC%" goto run
where gradle.bat >nul 2>nul
if errorlevel 1 goto bootstrap
call gradle.bat --version 2>nul | findstr /x /c:"Gradle 8.13" >nul
if errorlevel 1 goto bootstrap
call gradle.bat --project-dir "%APP_ROOT%" %*
exit /b %errorlevel%
:bootstrap
powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_ROOT%tools\bootstrap-gradle.ps1"
if errorlevel 1 exit /b %errorlevel%
:run
call "%GRADLE_EXEC%" --project-dir "%APP_ROOT%" %*
exit /b %errorlevel%
