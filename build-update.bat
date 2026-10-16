@echo off
echo ===================================================
echo Incremental Development Build for Practice Timer Overlay
echo ===================================================
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Development build failed!
    exit /b %ERRORLEVEL%
)
if not exist "release" mkdir release
copy /Y "app\build\outputs\apk\debug\*.apk" "release\"
echo [SUCCESS] Incremental development build completed. Artifacts saved to \release
