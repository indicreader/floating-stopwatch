@echo off
echo ===================================================
echo Clean Release Build for Practice Timer Overlay
echo ===================================================
call gradlew.bat clean assembleRelease
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Clean release build failed!
    exit /b %ERRORLEVEL%
)
if not exist "release" mkdir release
copy /Y "app\build\outputs\apk\release\*.apk" "release\"
powershell -NoProfile -Command "$json = Get-Content 'app\build\outputs\apk\release\output-metadata.json' | ConvertFrom-Json; $ver = $json.elements[0].versionName; Copy-Item 'app\build\outputs\apk\release\app-release.apk' \"release\PracticeTimerOverlay-v$ver.apk\" -Force; Write-Host \"[SUCCESS] Saved release\PracticeTimerOverlay-v$ver.apk\""
echo [SUCCESS] Release build completed. Artifacts saved to \release
