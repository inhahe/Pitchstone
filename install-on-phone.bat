@echo off
rem Builds the release app and installs it on the phone connected by USB.
rem The phone needs USB debugging on, and this computer allowed (see README.md).
setlocal

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

cd /d "%~dp0"

echo Building the release version...
call "%~dp0build-release.bat"
if errorlevel 1 (
    echo.
    echo Nothing was installed.
    exit /b 1
)

echo.
echo Installing on the phone (USB)...
rem -d: the device connected by USB, even while an emulator is running.
"%ADB%" -d install -r app\build\outputs\apk\release\app-release.apk
if errorlevel 1 (
    echo.
    echo Install failed. Check that the phone is connected and unlocked, that USB debugging
    echo is on, and that you accepted "Allow USB debugging" on the phone.
    echo "%ADB%" devices  shows what is connected.
    exit /b 1
)

echo.
echo Installed. Open "Pitchstone" on the phone.
