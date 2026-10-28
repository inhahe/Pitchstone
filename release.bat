@echo off
rem Publishes a release of Pitchstone on GitHub (github.com/inhahe/Pitchstone/releases): the
rem signed release APK, and R8's mapping file for reading that build's crash traces, tagged
rem v<versionName> (app\build.gradle.kts). Publishes nothing if that version is already
rem released: bump the version first (CLAUDE.md).
rem
rem   release.bat            build and publish
rem   release.bat --dry-run  build and write the release notes, but publish nothing
setlocal EnableExtensions

set "REPO=inhahe/Pitchstone"
set "DRY="
if /i "%~1"=="--dry-run" set "DRY=1"
if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-21.0.12.1+1"

cd /d "%~dp0"

rem The version: the text between the quotes on the versionName line.
set "VERSION="
for /f tokens^=2^ delims^=^" %%v in ('findstr /r /c:"^ *versionName *=" app\build.gradle.kts') do set "VERSION=%%v"
echo %VERSION%| findstr /r /x "[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*" >nul
if errorlevel 1 (
    echo Could not read versionName from app\build.gradle.kts ^(found "%VERSION%"^).
    exit /b 1
)
set "TAG=v%VERSION%"

where gh >nul 2>&1
if errorlevel 1 (
    echo The GitHub CLI ^(gh^) is needed: https://cli.github.com
    exit /b 1
)
gh auth status >nul 2>&1
if errorlevel 1 (
    echo gh is not logged in to GitHub: run  gh auth login
    exit /b 1
)

gh release view "%TAG%" --repo %REPO% >nul 2>&1
if not errorlevel 1 (
    echo Version %VERSION% is already released: https://github.com/%REPO%/releases/tag/%TAG%
    echo Nothing was published. Bump versionName and versionCode in app\build.gradle.kts first ^(see CLAUDE.md^).
    exit /b 1
)

rem What is released is the code on GitHub: everything committed, and pushed.
git diff --quiet HEAD --
if errorlevel 1 (
    echo There are uncommitted changes: commit and push them first. Nothing was published.
    exit /b 1
)
git fetch --quiet --tags origin
if errorlevel 1 (
    echo Could not reach GitHub. Nothing was published.
    exit /b 1
)
for /f %%h in ('git rev-parse HEAD') do set "HEAD=%%h"
set "UPSTREAM="
for /f %%h in ('git rev-parse @{u} 2^>nul') do set "UPSTREAM=%%h"
if not "%HEAD%"=="%UPSTREAM%" (
    echo This commit is not the one GitHub has: push ^(or pull^) first. Nothing was published.
    exit /b 1
)

if not exist keystore.properties (
    echo keystore.properties is missing, so the APK would be unsigned ^(see README.md, Building^).
    exit /b 1
)

echo Building Pitchstone %VERSION%...
rem By its full path: cmd may be set not to look in the current folder.
call "%~dp0gradlew.bat" assembleRelease
if errorlevel 1 (
    echo The build failed. Nothing was published.
    exit /b 1
)
set "APK=app\build\outputs\apk\release\app-release.apk"
set "MAPPING=app\build\outputs\mapping\release\mapping.txt"
if not exist "%APK%" (
    echo No signed APK at %APK%. Nothing was published.
    exit /b 1
)

set "OUT=%TEMP%\pitchstone-release-%VERSION%"
if exist "%OUT%" rd /s /q "%OUT%"
mkdir "%OUT%"
copy /y "%APK%" "%OUT%\Pitchstone-%VERSION%.apk" >nul
set "ASSETS="%OUT%\Pitchstone-%VERSION%.apk""
if exist "%MAPPING%" (
    copy /y "%MAPPING%" "%OUT%\Pitchstone-%VERSION%-mapping.txt" >nul
    set ASSETS=%ASSETS% "%OUT%\Pitchstone-%VERSION%-mapping.txt"
)

set "HASH="
for /f "delims=" %%h in ('certutil -hashfile "%OUT%\Pitchstone-%VERSION%.apk" SHA256 ^| findstr /v ":"') do set "HASH=%%h"

rem The release notes: how to install, the checksum, and what changed since the last release.
set "PREVIOUS="
for /f %%t in ('git describe --tags --abbrev^=0 --match "v*" HEAD 2^>nul') do set "PREVIOUS=%%t"
set "NOTES=%OUT%\notes.md"
> "%NOTES%" echo Pitchstone %VERSION%, markdown notes for Android ^(8.0 and later^).
>> "%NOTES%" echo.
>> "%NOTES%" echo **Install:** on the phone, download `Pitchstone-%VERSION%.apk` and open it; allow your browser or file manager to install apps when asked. A newer version installs over an older one, keeping your notes.
>> "%NOTES%" echo.
>> "%NOTES%" echo SHA-256 of the APK: `%HASH%`
>> "%NOTES%" echo.
if defined PREVIOUS (
    >> "%NOTES%" echo **Changes since %PREVIOUS%:**
    >> "%NOTES%" echo.
    git log --no-merges --pretty=format:"- %%s" %PREVIOUS%..HEAD >> "%NOTES%"
) else (
    >> "%NOTES%" echo The first release.
)

if defined DRY (
    echo.
    echo Dry run: would publish %TAG% at %HEAD% with %ASSETS%
    echo and these notes:
    echo.
    type "%NOTES%"
    echo.
    rd /s /q "%OUT%"
    exit /b 0
)

gh release create "%TAG%" %ASSETS% --repo %REPO% --target %HEAD% --title "Pitchstone %VERSION%" --notes-file "%NOTES%"
if errorlevel 1 (
    echo Publishing failed.
    rd /s /q "%OUT%"
    exit /b 1
)
rd /s /q "%OUT%"
git fetch --quiet --tags origin
echo.
echo Released: https://github.com/%REPO%/releases/tag/%TAG%
