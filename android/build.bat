@echo off
REM ============================================================
REM  TimeRecorder - build release APK (one-click)
REM  Output: app\build\outputs\apk\release\app-release.apk
REM  Edit the 3 paths below if your JDK / gradle location differs.
REM  NOTE: keep this file ASCII-only to avoid cmd code-page issues.
REM ============================================================
setlocal

REM ---- environment (edit if needed) ----
set "JAVA_HOME=C:\Users\Squirrelxzt\jdk21\jdk-21.0.12+8"
set "GRADLE_USER_HOME=D:\Android\.gradle"
set "GRADLE_BAT=C:\Users\Squirrelxzt\.gradle\wrapper\dists\gradle-8.5-bin\5hry6tgzq0wontdz18qo6fdj9\gradle-8.5\bin\gradle.bat"

cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK21 not found: %JAVA_HOME%
    goto :end
)
if not exist "%GRADLE_BAT%" (
    echo [ERROR] gradle.bat not found: %GRADLE_BAT%
    goto :end
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo Building release APK (JDK21, offline)...
echo.

call "%GRADLE_BAT%" assembleRelease --offline

if errorlevel 1 (
    echo.
    echo [FAILED] See errors above.
) else (
    echo.
    echo [OK] APK:
    echo     %~dp0app\build\outputs\apk\release\app-release.apk
)

:end
echo.
pause
