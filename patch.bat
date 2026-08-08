@echo off
setlocal enabledelayedexpansion

REM Build BiliDetox module and embed it into Bilibili APK with NPatch.
REM Usage: patch.bat [path-to-original-bilibili.apk]
REM Default input: original\iBiliPlayer-bili.apk
REM Output:        output\*-npatched.apk

set "SCRIPT_DIR=%~dp0"
set "MODULE_APK=%SCRIPT_DIR%app\build\outputs\apk\debug\app-debug.apk"
set "LAUNCHER_CLASS=%SCRIPT_DIR%tools\NPatchLauncher.java"
set "LAUNCHER_OUT=%SCRIPT_DIR%tools\build"
set "NPATCH_JAR=%SCRIPT_DIR%tools\npatch.jar"

if not exist "%NPATCH_JAR%" (
  echo [ERROR] NPatch jar not found: %NPATCH_JAR%
  echo Put jar-v1.0.6-698-release.jar at tools\npatch.jar
  exit /b 1
)
echo [INFO] NPatch: %NPATCH_JAR%

set "ORIG_APK=%~1"
if not defined ORIG_APK set "ORIG_APK=%SCRIPT_DIR%original\iBiliPlayer-bili.apk"
if not exist "%ORIG_APK%" (
  echo [ERROR] Original Bilibili APK not found: %ORIG_APK%
  echo Place an unmodified Bilibili APK in original\ or pass its path as argument.
  echo Usage: %~nx0 ^<path-to-apk^>
  exit /b 1
)
echo [INFO] Original APK: %ORIG_APK%

echo.
echo [1/4] Building module...
call "%SCRIPT_DIR%gradlew.bat" -p "%SCRIPT_DIR%." :app:assembleDebug --console=plain
if errorlevel 1 (
  echo [ERROR] Module build failed
  exit /b 1
)

echo.
echo [2/4] Compiling NPatchLauncher...
if not exist "%LAUNCHER_OUT%" mkdir "%LAUNCHER_OUT%"
javac -cp "%NPATCH_JAR%" -d "%LAUNCHER_OUT%" "%LAUNCHER_CLASS%"
if errorlevel 1 (
  echo [ERROR] NPatchLauncher compilation failed
  exit /b 1
)

echo.
echo [3/4] Patching APK (signature bypass disabled, see docs\BUILD.md)...
set "OUT_DIR=%SCRIPT_DIR%output"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

java -cp "%NPATCH_JAR%;%LAUNCHER_OUT%" NPatchLauncher "%ORIG_APK%" -m "%MODULE_APK%" -o "%OUT_DIR%" -f -l 0
if errorlevel 1 (
  echo [ERROR] NPatch failed
  exit /b 1
)

echo.
echo [4/4] Done. Output APK:
dir /b "%OUT_DIR%\*.apk"
echo.
echo Install with:
echo   adb install -r "%OUT_DIR%\iBiliPlayer-bili-698-npatched.apk"
endlocal
